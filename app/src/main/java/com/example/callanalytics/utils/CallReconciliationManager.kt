package com.example.callanalytics.utils

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import com.example.callanalytics.database.AppDatabase
import com.example.callanalytics.models.CallData
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class CallReconciliationManager(
    private val context: Context,
    private val database: AppDatabase,
    private val webhookManager: WebhookManager,
    private val webSocketManager: WebSocketManager
) {
    companion object {
        private const val TAG = "CallReconciliation"
        private const val PREFS_NAME = "call_reconciliation_prefs"
        private const val KEY_LAST_RECONCILIATION_TIMESTAMP = "last_reconciliation_timestamp"
    }

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /**
     * Main reconciliation method - call this after every call ends
     */
    suspend fun performReconciliation(agentCode: String, agentName: String) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔄 Starting call reconciliation for $agentCode")

            val lastTimestamp = getLastReconciliationTimestamp()
            val currentTimestamp = System.currentTimeMillis()

            Log.d(TAG, "📅 Reconciling calls from ${Date(lastTimestamp)} to ${Date(currentTimestamp)}")

            val missedCalls = findMissedCalls(lastTimestamp, currentTimestamp)

            if (missedCalls.isNotEmpty()) {
                Log.d(TAG, "🔍 Found ${missedCalls.size} missed calls to reconcile")

                for (callData in missedCalls) {
                    reconcileCall(callData, agentCode, agentName)
                }

                // Recalculate and update today's talk time
                updateTodaysTalkTime(agentCode, agentName)
            } else {
                Log.d(TAG, "✅ No missed calls found - data is consistent")
            }

            // Update reconciliation timestamp
            updateLastReconciliationTimestamp(currentTimestamp)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during reconciliation: ${e.message}", e)
        }
    }

    /**
     * Find calls in CallLog that are missing from our local database
     */
    private suspend fun findMissedCalls(fromTimestamp: Long, toTimestamp: Long): List<CallData> = withContext(Dispatchers.IO) {
        val missedCalls = mutableListOf<CallData>()

        try {
            if (context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "❌ No READ_CALL_LOG permission")
                return@withContext missedCalls
            }

            val uri = CallLog.Calls.CONTENT_URI
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )

            val selection = "${CallLog.Calls.DATE} > ? AND ${CallLog.Calls.DATE} <= ?"
            val selectionArgs = arrayOf(fromTimestamp.toString(), toTimestamp.toString())
            val sortOrder = "${CallLog.Calls.DATE} ASC"

            val cursor: Cursor? = context.contentResolver.query(
                uri, projection, selection, selectionArgs, sortOrder
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: "Unknown"
                    val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    val timestamp = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                    val duration = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION)) // Changed to Long

                    val callData = createCallDataFromLog(number, type, timestamp, duration)

                    // Check if this call already exists in our local database
                    val exists = isCallInLocalDatabase(callData)
                    if (!exists) {
                        Log.d(TAG, "🔍 Found missed call: ${callData.phoneNumber} at ${Date(timestamp)}")
                        missedCalls.add(callData)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error querying CallLog: ${e.message}", e)
        }

        return@withContext missedCalls
    }

    /**
     * Convert CallLog entry to CallData object
     */
    private fun createCallDataFromLog(number: String, type: Int, timestamp: Long, duration: Long): CallData {
        val callType = when (type) {
            CallLog.Calls.INCOMING_TYPE -> "incoming"
            CallLog.Calls.OUTGOING_TYPE -> "outgoing"
            CallLog.Calls.MISSED_TYPE -> "missed"
            else -> "unknown"
        }

        val contactName = getContactName(number)
        val date = Date(timestamp)
        val callDate = dateFormat.format(date)
        val startTime = timeFormat.format(date)
        val endTime = timeFormat.format(Date(timestamp + (duration * 1000)))

        return CallData(
            phoneNumber = number,
            contactName = contactName,
            callType = callType,
            talkDuration = if (callType != "missed") duration else 0L, // Fixed: Long and 0L
            totalDuration = duration, // Already Long
            callDate = callDate,
            startTime = startTime, // Added
            endTime = endTime, // Added
            agentCode = "", // Will be set when processing
            agentName = "", // Will be set when processing
            timestamp = timestamp,
            dataSource = "reconciled" // Mark as reconciled data
        )
    }

    /**
     * Check if call already exists in local database using fingerprint matching
     */
    private suspend fun isCallInLocalDatabase(callData: CallData): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val fingerprint = createCallFingerprint(callData)

            // Query local database for this call
            val existingCalls = database.callDao().getCallsByTimestampRange(
                callData.timestamp - 30000, // 30 seconds before
                callData.timestamp + 30000  // 30 seconds after
            )

            existingCalls.any { createCallFingerprint(it) == fingerprint }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error checking local database: ${e.message}")
            false
        }
    }

    /**
     * Create unique fingerprint for call matching
     */
    private fun createCallFingerprint(callData: CallData): String {
        return "${callData.timestamp}_${callData.phoneNumber}_${callData.callType}_${callData.talkDuration}"
    }

    /**
     * Process a single missed call - add to DB, send webhook, update analytics
     */
    private suspend fun reconcileCall(callData: CallData, agentCode: String, agentName: String) = withContext(Dispatchers.IO) {
        try {
            // Set agent info
            val reconcileCallData = callData.copy(
                agentCode = agentCode,
                agentName = agentName
            )

            Log.d(TAG, "📞 Reconciling call: ${reconcileCallData.phoneNumber} (${reconcileCallData.callType})")

            // 1. Save to local database
            database.callDao().insertCall(reconcileCallData)
            Log.d(TAG, "💾 Saved reconciled call to local database")

            // 2. Send webhook to n8n
            try {
                webhookManager.sendWebhook(reconcileCallData)
                Log.d(TAG, "🔗 Sent reconciled call webhook")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to send reconciled webhook: ${e.message}")
            }

            // 3. Send real-time update via WebSocket
            try {
                webSocketManager.sendCallUpdate(reconcileCallData)
                Log.d(TAG, "🌐 Sent reconciled call WebSocket update")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to send reconciled WebSocket: ${e.message}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error reconciling call: ${e.message}", e)
        }
    }

    /**
     * Recalculate today's talk time and send update to server
     */
    private suspend fun updateTodaysTalkTime(agentCode: String, agentName: String) = withContext(Dispatchers.IO) {
        try {
            val today = dateFormat.format(Date())
            val todaysTalkTime = database.callDao().getTodaysTalkTime(agentCode, today)

            Log.d(TAG, "📊 Updated today's talk time: ${todaysTalkTime}s for $agentCode")

            // Send updated talk time via WebSocket
            webSocketManager.sendTalkTimeUpdate(agentCode, agentName, todaysTalkTime.toInt())

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating today's talk time: ${e.message}")
        }
    }

    /**
     * Get contact name from phone number
     */
    private fun getContactName(phoneNumber: String): String? {
        return try {
            if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return null
            }

            val cleanNumber = phoneNumber.replace("[^\\d+]".toRegex(), "")
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)

            val cursor = context.contentResolver.query(
                uri,
                projection,
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
                arrayOf("%$cleanNumber%"),
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        return it.getString(nameIndex)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error looking up contact: ${e.message}")
            null
        }
    }

    /**
     * Initialize reconciliation on first app launch
     */
    fun initializeReconciliation() {
        if (getLastReconciliationTimestamp() == 0L) {
            // First time - set to 24 hours ago to catch recent calls
            val yesterday = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
            updateLastReconciliationTimestamp(yesterday)
            Log.d(TAG, "🚀 Initialized reconciliation timestamp to 24 hours ago")
        }
    }

    /**
     * Get last reconciliation timestamp from SharedPreferences
     */
    private fun getLastReconciliationTimestamp(): Long {
        return sharedPreferences.getLong(KEY_LAST_RECONCILIATION_TIMESTAMP, 0L)
    }

    /**
     * Update last reconciliation timestamp
     */
    private fun updateLastReconciliationTimestamp(timestamp: Long) {
        sharedPreferences.edit()
            .putLong(KEY_LAST_RECONCILIATION_TIMESTAMP, timestamp)
            .apply()
        Log.d(TAG, "⏰ Updated reconciliation timestamp to ${Date(timestamp)}")
    }
}