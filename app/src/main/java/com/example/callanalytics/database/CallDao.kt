package com.example.callanalytics.database

import androidx.room.*
import com.example.callanalytics.models.CallData
import com.example.callanalytics.models.FailedWebhook
import kotlinx.coroutines.flow.Flow

@Dao
interface CallDao {
    @Query("SELECT * FROM calls ORDER BY timestamp DESC LIMIT 50")
    fun getRecentCalls(): Flow<List<CallData>>

    @Query("SELECT * FROM calls WHERE callDate = :date ORDER BY timestamp DESC")
    suspend fun getCallsForDate(date: String): List<CallData>

    @Query("SELECT * FROM calls WHERE callDate = :date")
    suspend fun getAnalyticsForDate(date: String): List<CallData>



    @Insert
    suspend fun insertCall(call: CallData): Long

    @Update
    suspend fun updateCall(call: CallData)

    @Query("DELETE FROM calls WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOldCalls(cutoffTimestamp: Long)

    @Query("SELECT * FROM failed_webhooks ORDER BY failedTimestamp DESC")
    suspend fun getFailedWebhooks(): List<FailedWebhook>

    @Insert
    suspend fun insertFailedWebhook(webhook: FailedWebhook)

    @Delete
    suspend fun deleteFailedWebhook(webhook: FailedWebhook)

    @Query("DELETE FROM failed_webhooks")
    suspend fun clearFailedWebhooks()

    // REPLACE the previous methods with these corrected ones

    @Query("SELECT * FROM calls WHERE timestamp BETWEEN :startTime AND :endTime")
    suspend fun getCallsByTimestampRange(startTime: Long, endTime: Long): List<CallData>

    @Query("SELECT COALESCE(SUM(talkDuration), 0) FROM calls WHERE agentCode = :agentCode AND callDate = :date")
    suspend fun getTodaysTalkTime(agentCode: String, date: String): Long

    @Query("SELECT * FROM calls WHERE agentCode = :agentCode AND callDate = :date ORDER BY timestamp DESC")
    suspend fun getCallsByAgentAndDate(agentCode: String, date: String): List<CallData>
}