package com.example.callanalytics.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.*

@Entity(tableName = "calls")
data class CallData(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val phoneNumber: String,
    val contactName: String? = null,
    val callType: String, // incoming, outgoing, missed
    val talkDuration: Long, // Talk time in seconds (after pickup)
    val totalDuration: Long, // Total time in seconds (including ring)
    val callDate: String, // YYYY-MM-DD format
    val startTime: String, // HH:MM:SS format
    val endTime: String, // HH:MM:SS format
    val agentCode: String,
    val agentName: String,
    val webhookSent: Boolean = false,
    val retryCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val dataSource: String = "real_time" // ADD THIS LINE
) {
    fun toWebhookJson(): String {
        val startTimeISO = formatTimestampISO(timestamp)
        val deviceId = "CA_${agentCode}_${System.currentTimeMillis()}"

        return """
        {
            "agentCode": "$agentCode",
            "agentName": "$agentName",
            "contactName": ${if (contactName != null) "\"$contactName\"" else "null"},
            "phoneNumber": "$phoneNumber",
            "callType": "$callType",
            "talkDuration": $talkDuration,
            "totalDuration": $totalDuration,
            "callDate": "$callDate",
            "startTime": "$startTime",
            "endTime": "$endTime",
            "timestamp": "$startTimeISO",
            "deviceId": "$deviceId"
        }
        """.trimIndent()
    }

    private fun formatTimestampISO(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(timestamp))
    }
}

@Entity(tableName = "failed_webhooks")
data class FailedWebhook(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val callDataJson: String,
    val failedTimestamp: Long = System.currentTimeMillis(),
    val retryCount: Int = 0
)

data class DailyAnalytics(
    val date: String,
    val totalCalls: Int,
    val totalDuration: Long,
    val incomingCalls: Int,
    val incomingDuration: Long,
    val outgoingCalls: Int,
    val outgoingDuration: Long,
    val missedCalls: Int
)