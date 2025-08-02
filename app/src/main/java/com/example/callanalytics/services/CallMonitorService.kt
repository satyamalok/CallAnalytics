package com.example.callanalytics.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.callanalytics.R
import com.example.callanalytics.database.AppDatabase
import com.example.callanalytics.models.CallData
import com.example.callanalytics.utils.WebhookManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class CallMonitorService : Service() {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var database: AppDatabase
    private lateinit var webhookManager: WebhookManager
    private var serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // Call tracking variables
    private var callStartTime = 0L
    private var callAnswerTime = 0L
    private var isCallActive = false
    private var lastCallLogCount = -1

    companion object {
        private const val TAG = "CallMonitorService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "call_monitor_channel"
    }

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences("CallAnalytics", Context.MODE_PRIVATE)
        database = AppDatabase.getDatabase(this)
        webhookManager = WebhookManager(this)

        createNotificationChannel()
        Log.d(TAG, "🚀 CallMonitorService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("Monitoring calls..."))

        val phoneState = intent?.getStringExtra("PHONE_STATE")
        val phoneNumber = intent?.getStringExtra("PHONE_NUMBER")

        Log.d(TAG, "📱 Service command: State=$phoneState, Number=$phoneNumber")

        when (phoneState) {
            "RINGING" -> handleRingingState(phoneNumber)
            "OFFHOOK" -> handleOffhookState()
            "IDLE" -> handleIdleState()
        }

        return START_STICKY
    }

    private fun handleRingingState(phoneNumber: String?) {
        if (!isCallActive) {
            callStartTime = System.currentTimeMillis()
            isCallActive = true
            Log.d(TAG, "📲 Call started (RINGING): $phoneNumber at ${Date(callStartTime)}")
        }
    }

    private fun handleOffhookState() {
        if (isCallActive && callAnswerTime == 0L) {
            callAnswerTime = System.currentTimeMillis()
            Log.d(TAG, "📞 Call answered (OFFHOOK) at ${Date(callAnswerTime)}")
        } else if (!isCallActive) {
            // Outgoing call
            callStartTime = System.currentTimeMillis()
            callAnswerTime = System.currentTimeMillis()
            isCallActive = true
            Log.d(TAG, "📞 Outgoing call started at ${Date(callStartTime)}")
        }
    }

    private fun handleIdleState() {
        if (isCallActive) {
            val callEndTime = System.currentTimeMillis()
            Log.d(TAG, "📴 Call ended at ${Date(callEndTime)}")

            // Wait a bit for call log to update, then process
            serviceScope.launch {
                delay(2000) // Wait 2 seconds for call log to update
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

                // Calculate durations
                val totalDuration = if (callStartTime > 0) (callEndTime - callStartTime) / 1000 else lastCall.totalDuration
                val talkDuration = if (callAnswerTime > 0 && lastCall.callType != "missed") {
                    (callEndTime - callAnswerTime) / 1000
                } else {
                    lastCall.talkDuration
                }

                val finalCallData = lastCall.copy(
                    totalDuration = totalDuration,
                    talkDuration = talkDuration
                )

                // Save to database
                val callId = database.callDao().insertCall(finalCallData)
                Log.d(TAG, "💾 Call saved with ID: $callId")

                // Send webhook
                webhookManager.sendWebhook(finalCallData.copy(id = callId))

            } else {
                Log.w(TAG, "⚠️ No call found in call log")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing last call", e)
        }
    }

    private fun getLastCallFromLog(): CallData? {
        return try {
            val cursor: Cursor? = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.DATE
                ),
                null,
                null,
                "${CallLog.Calls.DATE} DESC" // Remove LIMIT from here
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val phoneNumber = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: "Unknown"
                    val callType = when (it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))) {
                        CallLog.Calls.INCOMING_TYPE -> "incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        else -> "unknown"
                    }
                    val duration = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                    val callDate = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))

                    // Get contact name
                    val contactName = getContactName(phoneNumber)

                    // Get agent info
                    val agentCode = sharedPreferences.getString("agentCode", "Agent1") ?: "Agent1"
                    val agentName = sharedPreferences.getString("agentName", "Unknown") ?: "Unknown"

                    // Format dates and times
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    val startTime = timeFormat.format(Date(callDate))
                    val endTime = timeFormat.format(Date(callDate + (duration * 1000)))

                    CallData(
                        phoneNumber = phoneNumber,
                        contactName = contactName,
                        callType = callType,
                        talkDuration = duration,
                        totalDuration = duration,
                        callDate = dateFormat.format(Date(callDate)),
                        startTime = startTime,
                        endTime = endTime,
                        agentCode = agentCode,
                        agentName = agentName,
                        timestamp = callDate
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error reading call log", e)
            null
        }
    }

    private fun getContactName(phoneNumber: String): String? {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )

            val cursor = contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        return it.getString(nameIndex)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error looking up contact name for $phoneNumber", e)
            null
        }
    }

    private fun resetCallTracking() {
        callStartTime = 0L
        callAnswerTime = 0L
        isCallActive = false
        Log.d(TAG, "🔄 Call tracking reset")
    }

    private fun createNotificationChannel() {
        // API level check for notification channels
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors phone calls for analytics"
                setSound(null, null)
                enableVibration(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Call Analytics")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        Log.d(TAG, "🛑 CallMonitorService destroyed")
    }
}