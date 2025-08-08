package com.example.callanalytics.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.os.Build
import android.os.IBinder
import android.provider.CallLog
import android.provider.ContactsContract
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
    private var currentCallNumber: String? = null
    private var currentCallType: String? = null
    private var lastProcessedCallTimestamp = 0L  // NEW: Prevent duplicate processing

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
            currentCallNumber = phoneNumber
            currentCallType = "incoming"
            Log.d(TAG, "📲 Incoming call started: $phoneNumber at ${Date(callStartTime)}")

            // NEW: Send WebSocket immediately for incoming calls (ringing state)
            if (currentCallNumber != null) {
                webSocketManager.sendCallStarted(currentCallNumber!!, "incoming")
            }
        }
    }

    private fun handleOffhookState(phoneNumber: String?) {
        if (isCallActive && callAnswerTime == 0L && currentCallType == "incoming") {
            // Incoming call answered - just record answer time, don't send WebSocket again
            callAnswerTime = System.currentTimeMillis()
            Log.d(TAG, "📞 Incoming call answered at ${Date(callAnswerTime)}")

        } else if (!isCallActive) {
            // Outgoing call started
            callStartTime = System.currentTimeMillis()
            callAnswerTime = System.currentTimeMillis()
            isCallActive = true
            currentCallNumber = phoneNumber ?: "Unknown"
            currentCallType = "outgoing"
            Log.d(TAG, "📞 Outgoing call started at ${Date(callStartTime)}")

            // Send WebSocket for outgoing call
            webSocketManager.sendCallStarted(currentCallNumber!!, "outgoing")
        }
    }

    private fun handleIdleState() {
        if (isCallActive) {
            val callEndTime = System.currentTimeMillis()
            Log.d(TAG, "📴 Call ended at ${Date(callEndTime)}")

            // Wait a bit for call log to update, then process
            serviceScope.launch {
                delay(3000)  // Increased delay to ensure call log is updated
                processLastCall(callEndTime)
                resetCallTracking()
            }
        }
    }

    private suspend fun processLastCall(callEndTime: Long) {
        try {
            val lastCall = getLastCallFromLog()
            if (lastCall != null) {

                // DUPLICATE PREVENTION: Check if we already processed this call
                if (lastCall.timestamp <= lastProcessedCallTimestamp) {
                    Log.d(TAG, "⚠️ Call already processed, skipping duplicate")
                    return
                }

                Log.d(TAG, "📋 Processing call: ${lastCall.phoneNumber}")

                // Calculate ACTUAL durations
                val calculatedTotalDuration = if (callStartTime > 0) {
                    (callEndTime - callStartTime) / 1000
                } else {
                    lastCall.totalDuration
                }

                val calculatedTalkDuration = if (callAnswerTime > 0 && currentCallType == "incoming") {
                    // For incoming calls, use our calculation
                    val ourCalculation = (callEndTime - callAnswerTime) / 1000
                    val callLogDuration = lastCall.talkDuration
                    if (callLogDuration > 0) callLogDuration else ourCalculation
                } else {
                    // For outgoing calls, use call log duration
                    lastCall.talkDuration
                }

                Log.d(TAG, "⏱️ Duration Calculation:")
                Log.d(TAG, "   Call Type: $currentCallType")
                Log.d(TAG, "   Call Start: ${if (callStartTime > 0) Date(callStartTime) else "Not recorded"}")
                Log.d(TAG, "   Call Answer: ${if (callAnswerTime > 0) Date(callAnswerTime) else "Not answered"}")
                Log.d(TAG, "   Call End: ${Date(callEndTime)}")
                Log.d(TAG, "   Total Duration: ${calculatedTotalDuration}s")
                Log.d(TAG, "   Talk Duration: ${calculatedTalkDuration}s")

                val finalCallData = lastCall.copy(
                    totalDuration = calculatedTotalDuration,
                    talkDuration = calculatedTalkDuration,
                    contactName = getContactName(lastCall.phoneNumber)  // Add contact lookup
                )

                // Save to database
                val callId = database.callDao().insertCall(finalCallData)
                Log.d(TAG, "💾 Call saved with ID: $callId")

                // Update last processed timestamp to prevent duplicates
                lastProcessedCallTimestamp = lastCall.timestamp

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

                val cursor: Cursor? = contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC"
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
                            contactName = null, // Will be filled by getContactName
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
        return try {
            // Check if we have permission
            if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "📱 No READ_CONTACTS permission")
                return null
            }

            // Clean the phone number for better matching
            val cleanNumber = phoneNumber.replace("[^\\d+]".toRegex(), "")
            Log.d(TAG, "📱 Looking up contact for: $phoneNumber (cleaned: $cleanNumber)")

            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            // Try multiple query variations for better matching
            val queries = listOf(
                phoneNumber,
                cleanNumber,
                if (cleanNumber.startsWith("+91")) cleanNumber.substring(3) else cleanNumber,
                if (cleanNumber.length == 10) "+91$cleanNumber" else cleanNumber
            )

            for (queryNumber in queries) {
                val cursor = contentResolver.query(
                    uri,
                    projection,
                    "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
                    arrayOf("%$queryNumber%"),
                    null
                )

                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                        if (nameIndex >= 0) {
                            val contactName = it.getString(nameIndex)
                            val contactNumber = if (numberIndex >= 0) it.getString(numberIndex) else "unknown"
                            Log.d(TAG, "📱 Contact found: $contactName ($contactNumber) for query: $queryNumber")
                            return contactName
                        }
                    }
                }
            }

            Log.d(TAG, "📱 No contact found for: $phoneNumber")
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error looking up contact: ${e.message}")
            null
        }
    }

    private fun resetCallTracking() {
        Log.d(TAG, "🔄 Call tracking reset")
        isCallActive = false
        callStartTime = 0
        callAnswerTime = 0
        currentCallNumber = null
        currentCallType = null
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