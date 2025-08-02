package com.example.callanalytics.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.os.Build
import android.os.IBinder
import android.provider.CallLog
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import com.example.callanalytics.MainActivity
import com.example.callanalytics.R
import com.example.callanalytics.database.AppDatabase
import com.example.callanalytics.models.CallData
import com.example.callanalytics.utils.WebhookManager
import com.example.callanalytics.utils.WebSocketManager
import java.text.SimpleDateFormat
import java.util.*

class CallMonitorService : Service() {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var database: AppDatabase
    private lateinit var webhookManager: WebhookManager
    private lateinit var webSocketManager: WebSocketManager

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    // Call tracking variables
    private var isCallActive = false
    private var callStartTime = 0L
    private var callAnswerTime = 0L
    private var lastProcessedCall: CallData? = null

    companion object {
        private const val TAG = "CallMonitorService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "CallMonitorChannel"
    }

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences("CallAnalytics", Context.MODE_PRIVATE)
        database = AppDatabase.getDatabase(this)
        webhookManager = WebhookManager(this)
        webSocketManager = WebSocketManager.getInstance(this)

        createNotificationChannel()
        Log.d(TAG, "🚀 CallMonitorService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val phoneState = intent?.getStringExtra("PHONE_STATE")
        val phoneNumber = intent?.getStringExtra("PHONE_NUMBER")

        startForegroundService()

        Log.d(TAG, "📱 Service command: State=$phoneState, Number=$phoneNumber")

        when (phoneState) {
            "RINGING" -> handleRingingState(phoneNumber)
            "OFFHOOK" -> handleOffhookState(phoneNumber)
            "IDLE" -> handleIdleState()
        }

        return START_STICKY
    }

    private fun handleRingingState(phoneNumber: String?) {
        if (!isCallActive) {
            callStartTime = System.currentTimeMillis()
            isCallActive = true
            Log.d(TAG, "📲 Call started (RINGING): $phoneNumber at ${Date(callStartTime)}")

            // Send WebSocket event for incoming call
            if (phoneNumber != null) {
                webSocketManager.sendCallStarted(phoneNumber, "incoming")
            }
        }
    }

    private fun handleOffhookState(phoneNumber: String?) {
        if (isCallActive && callAnswerTime == 0L) {
            // Incoming call answered
            callAnswerTime = System.currentTimeMillis()
            Log.d(TAG, "📞 Incoming call answered at ${Date(callAnswerTime)}")

        } else if (!isCallActive) {
            // Outgoing call started
            callStartTime = System.currentTimeMillis()
            callAnswerTime = System.currentTimeMillis()
            isCallActive = true
            Log.d(TAG, "📞 Outgoing call started at ${Date(callStartTime)}")

            // Send WebSocket event for outgoing call
            val number = phoneNumber ?: "Unknown"
            webSocketManager.sendCallStarted(number, "outgoing")
        }
    }

    private fun handleIdleState() {
        if (isCallActive) {
            val callEndTime = System.currentTimeMillis()
            Log.d(TAG, "📴 Call ended at ${Date(callEndTime)}")

            // Wait a bit for call log to update, then process
            serviceScope.launch {
                delay(2000)
                processLastCall(callEndTime)
                resetCallTracking()
            }
        }
    }

    private suspend fun processLastCall(callEndTime: Long) {
        try {
            val lastCall = getLastCallFromLog()
            if (lastCall != null) {
                Log.d(TAG, "📋 Processing call: ${lastCall.phoneNumber}")

                // Calculate ACTUAL durations
                val calculatedTotalDuration = if (callStartTime > 0) {
                    (callEndTime - callStartTime) / 1000
                } else {
                    lastCall.totalDuration // fallback to call log duration
                }

                val calculatedTalkDuration = if (callAnswerTime > 0) {
                    // Use the smaller of: our calculation vs call log duration
                    val ourCalculation = (callEndTime - callAnswerTime) / 1000
                    val callLogDuration = lastCall.talkDuration

                    // Call log is more accurate for talk time, use it if available
                    if (callLogDuration > 0) callLogDuration else ourCalculation
                } else {
                    // No answer time recorded, probably missed call
                    0L
                }

                Log.d(TAG, "⏱️ Duration Calculation:")
                Log.d(TAG, "   Call Start: ${if (callStartTime > 0) Date(callStartTime) else "Not recorded"}")
                Log.d(TAG, "   Call Answer: ${if (callAnswerTime > 0) Date(callAnswerTime) else "Not answered"}")
                Log.d(TAG, "   Call End: ${Date(callEndTime)}")
                Log.d(TAG, "   Total Duration: ${calculatedTotalDuration}s")
                Log.d(TAG, "   Talk Duration: ${calculatedTalkDuration}s")
                Log.d(TAG, "   CallLog Duration: ${lastCall.talkDuration}s")

                val finalCallData = lastCall.copy(
                    totalDuration = calculatedTotalDuration,
                    talkDuration = calculatedTalkDuration
                )

                // Save to database
                val callId = database.callDao().insertCall(finalCallData)
                Log.d(TAG, "💾 Call saved with ID: $callId")

                // Send webhook (EXISTING FUNCTIONALITY - PRESERVED)
                webhookManager.sendWebhook(finalCallData.copy(id = callId))
                Log.d(TAG, "🔗 Webhook sent for call ID: $callId")

                // Send WebSocket event (NEW FUNCTIONALITY - ADDED)
                webSocketManager.sendCallEnded(finalCallData.copy(id = callId))
                Log.d(TAG, "📡 WebSocket event sent for call ID: $callId")

            } else {
                Log.w(TAG, "⚠️ No call found in call log")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing last call", e)
        }
    }

    private suspend fun getLastCallFromLog(): CallData? {
        return withContext(Dispatchers.IO) {
            try {
                val projection = arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.DATE
                )

                // Fix: Remove LIMIT from the sortOrder parameter
                val cursor: Cursor? = contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC" // Removed "LIMIT 1" from here
                )

                cursor?.use {
                    if (it.moveToFirst()) {
                        val numberIndex = it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                        val typeIndex = it.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                        val durationIndex = it.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                        val dateIndex = it.getColumnIndexOrThrow(CallLog.Calls.DATE)

                        val phoneNumber = it.getString(numberIndex) ?: "Unknown"
                        val callTypeInt = it.getInt(typeIndex)
                        val duration = it.getLong(durationIndex)
                        val callTimestamp = it.getLong(dateIndex)

                        val callType = when (callTypeInt) {
                            CallLog.Calls.INCOMING_TYPE -> "incoming"
                            CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                            CallLog.Calls.MISSED_TYPE -> "missed"
                            else -> "unknown"
                        }

                        val agentCode = sharedPreferences.getString("agentCode", "Agent1") ?: "Agent1"
                        val agentName = sharedPreferences.getString("agentName", "Unknown") ?: "Unknown"

                        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

                        val callDate = dateFormat.format(Date(callTimestamp))
                        val startTime = timeFormat.format(Date(callTimestamp))
                        val endTime = timeFormat.format(Date(callTimestamp + (duration * 1000)))

                        return@withContext CallData(
                            phoneNumber = phoneNumber,
                            contactName = getContactName(phoneNumber),
                            callType = callType,
                            talkDuration = duration,
                            totalDuration = duration,
                            callDate = callDate,
                            startTime = startTime,
                            endTime = endTime,
                            agentCode = agentCode,
                            agentName = agentName,
                            timestamp = callTimestamp
                        )
                    }
                }
                null
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error reading call log", e)
                null
            }
        }
    }

    private fun getContactName(phoneNumber: String): String? {
        // Simple contact lookup - you can expand this
        return null
    }

    private fun resetCallTracking() {
        Log.d(TAG, "🔄 Call tracking reset")
        isCallActive = false
        callStartTime = 0
        callAnswerTime = 0
        lastProcessedCall = null
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Call Analytics Active")
            .setContentText("Monitoring calls and sending data to server")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Channel for call monitoring service"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "🛑 CallMonitorService destroyed")
    }
}