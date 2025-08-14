package com.example.callanalytics.utils

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.example.callanalytics.services.OverlayService
import java.net.URISyntaxException

// ADD this import at the top with other imports
import com.example.callanalytics.models.CallData

class WebSocketManager(private val context: Context) {

    private var socket: Socket? = null
    private var sharedPreferences: SharedPreferences
    private var isConnected = false
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    companion object {
        private const val TAG = "WebSocketManager"
        private const val SERVER_URL = "https://analytics.tsblive.in"

        @Volatile
        private var INSTANCE: WebSocketManager? = null

        fun getInstance(context: Context): WebSocketManager {
            return INSTANCE ?: synchronized(this) {
                val instance = WebSocketManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    init {
        sharedPreferences = context.getSharedPreferences("CallAnalytics", Context.MODE_PRIVATE)
    }

    fun connect() {
        try {
            Log.d(TAG, "🔌 Connecting to WebSocket server: $SERVER_URL")

            val options = IO.Options().apply {
                timeout = 10000
                reconnection = true
                reconnectionAttempts = 5
                reconnectionDelay = 2000
            }

            socket = IO.socket(SERVER_URL, options)
            setupEventListeners()
            socket?.connect()

        } catch (e: URISyntaxException) {
            Log.e(TAG, "❌ Invalid server URL: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Connection error: ${e.message}")
        }
    }

    private fun setupEventListeners() {
        socket?.apply {
            on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "✅ WebSocket connected")
                isConnected = true
                reconnectJob?.cancel()
                sendAgentOnline()
            }

            on(Socket.EVENT_DISCONNECT) {
                Log.d(TAG, "🔌 WebSocket disconnected")
                isConnected = false
                startReconnectTimer()
            }

            on(Socket.EVENT_CONNECT_ERROR) { args ->
                val error = if (args.isNotEmpty()) args[0].toString() else "Unknown error"
                Log.e(TAG, "❌ Connection error: $error")
                isConnected = false
                startReconnectTimer()
            }

            on("agent_status") { args ->
                if (args.isNotEmpty()) {
                    try {
                        val data = args[0] as JSONObject
                        Log.d(TAG, "📡 Agent status: ${data.getString("status")}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing agent status: ${e.message}")
                    }
                }
            }

            on("dashboard_update") { args ->
                if (args.isNotEmpty()) {
                    Log.d(TAG, "📊 Dashboard update received")
                }
            }

            on("error") { args ->
                val error = if (args.isNotEmpty()) args[0].toString() else "Unknown error"
                Log.e(TAG, "❌ Server error: $error")
            }

            on("pong") {
                Log.d(TAG, "🏓 Pong received")
            }

            // NEW: Reminder trigger handler
            on("reminder_trigger") { args ->
                if (args.isNotEmpty()) {
                    try {
                        val data = args[0] as JSONObject
                        val action = data.getString("action")
                        val message = data.getString("message")
                        val idleTime = data.getString("idleTime")
                        val agentCode = data.getString("agentCode")

                        Log.d(TAG, "📱 Reminder trigger received: $message")

                        if (action == "show_reminder") {
                            // Show overlay notification
                            showOverlayNotification(message, idleTime)

                            // Send acknowledgment back to server
                            sendReminderAcknowledgment(agentCode)
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error processing reminder trigger: ${e.message}")
                    }
                }
            }
        }
    }

    private fun showOverlayNotification(message: String, idleTime: String) {
        try {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SHOW_OVERLAY
                putExtra(OverlayService.EXTRA_MESSAGE, message)
                putExtra(OverlayService.EXTRA_IDLE_TIME, idleTime)
            }

            context.startService(intent)
            Log.d(TAG, "📱 Overlay notification triggered: $message")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing overlay notification: ${e.message}")
        }
    }

    private fun sendReminderAcknowledgment(agentCode: String) {
        if (!isConnected) {
            Log.w(TAG, "⚠️ Not connected, cannot send reminder acknowledgment")
            return
        }

        try {
            val data = JSONObject().apply {
                put("action", "reminder_acknowledged")
                put("agentCode", agentCode)
                put("timestamp", System.currentTimeMillis())
            }

            socket?.emit("reminder_acknowledged", data)
            Log.d(TAG, "✅ Reminder acknowledgment sent for $agentCode")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending reminder acknowledgment: ${e.message}")
        }
    }

    private fun startReconnectTimer() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5000) // Wait 5 seconds before reconnecting
            if (!isConnected) {
                Log.d(TAG, "🔄 Attempting to reconnect...")
                connect()
            }
        }
    }

    fun sendAgentOnline() {
        if (!isConnected) {
            Log.w(TAG, "⚠️ Not connected, cannot send agent_online")
            return
        }

        try {
            val agentCode = sharedPreferences.getString("agentCode", "Agent1") ?: "Agent1"
            val agentName = sharedPreferences.getString("agentName", "Unknown") ?: "Unknown"

            val data = JSONObject().apply {
                put("event", "agent_online")
                put("agentCode", agentCode)
                put("agentName", agentName)
                put("timestamp", System.currentTimeMillis())
            }

            socket?.emit("agent_online", data)
            Log.d(TAG, "📤 Sent agent_online: $agentCode ($agentName)")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending agent_online: ${e.message}")
        }
    }

    fun sendAgentOffline() {
        if (!isConnected) return

        try {
            val agentCode = sharedPreferences.getString("agentCode", "Agent1") ?: "Agent1"

            val data = JSONObject().apply {
                put("event", "agent_offline")
                put("agentCode", agentCode)
                put("timestamp", System.currentTimeMillis())
            }

            socket?.emit("agent_offline", data)
            Log.d(TAG, "📤 Sent agent_offline: $agentCode")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending agent_offline: ${e.message}")
        }
    }

    fun sendCallStarted(phoneNumber: String, callType: String) {
        if (!isConnected) {
            Log.w(TAG, "⚠️ Not connected, cannot send call_started")
            return
        }

        try {
            val agentCode = sharedPreferences.getString("agentCode", "Agent1") ?: "Agent1"
            val agentName = sharedPreferences.getString("agentName", "Unknown") ?: "Unknown"

            val data = JSONObject().apply {
                put("event", "call_started")
                put("agentCode", agentCode)
                put("agentName", agentName)
                put("phoneNumber", phoneNumber)
                put("callType", callType)
                put("timestamp", System.currentTimeMillis())
            }

            socket?.emit("call_started", data)
            Log.d(TAG, "📤 Sent call_started: $agentCode -> $phoneNumber ($callType)")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending call_started: ${e.message}")
        }
    }

    // 🎯 ENHANCED: Now includes today's total talk time
    fun sendCallEnded(callData: com.example.callanalytics.models.CallData, todayTotalTalkTime: Long = 0L) {
        if (!isConnected) {
            Log.w(TAG, "⚠️ Not connected, cannot send call_ended")
            return
        }

        try {
            val agentCode = sharedPreferences.getString("agentCode", "Agent1") ?: "Agent1"
            val agentName = sharedPreferences.getString("agentName", "Unknown") ?: "Unknown"

            val callDataJson = JSONObject().apply {
                put("phoneNumber", callData.phoneNumber)
                put("contactName", callData.contactName ?: JSONObject.NULL)
                put("callType", callData.callType)
                put("talkDuration", callData.talkDuration)
                put("totalDuration", callData.totalDuration)
                put("callDate", callData.callDate)
                put("startTime", callData.startTime)
                put("endTime", callData.endTime)
                put("agentName", agentName)
            }

            val data = JSONObject().apply {
                put("event", "call_ended")
                put("agentCode", agentCode)
                put("callData", callDataJson)
                put("todayTotalTalkTime", todayTotalTalkTime) // 🎯 NEW: Today's total talk time
                put("timestamp", System.currentTimeMillis())
            }

            socket?.emit("call_ended", data)
            Log.d(TAG, "📤 Sent call_ended: $agentCode -> ${callData.phoneNumber} (${callData.talkDuration}s, total today: ${todayTotalTalkTime}s)")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending call_ended: ${e.message}")
        }
    }

    fun sendPing() {
        if (isConnected) {
            socket?.emit("ping")
            Log.d(TAG, "🏓 Ping sent")
        }
    }

    fun disconnect() {
        try {
            sendAgentOffline()
            reconnectJob?.cancel()
            socket?.disconnect()
            socket?.off()
            isConnected = false
            Log.d(TAG, "🔌 WebSocket disconnected manually")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error disconnecting: ${e.message}")
        }
    }

    fun isConnected(): Boolean = isConnected

    fun getConnectionStatus(): String {
        return when {
            isConnected -> "Connected"
            socket?.connected() == true -> "Connecting"
            else -> "Disconnected"
        }
    }

    // REPLACE the existing sendCallUpdate method with this:
    suspend fun sendCallUpdate(callData: CallData) {
        if (!isConnected) {
            Log.w(TAG, "⚠️ Not connected, cannot send call_update")
            return
        }

        try {
            val callUpdateData = JSONObject().apply {
                put("action", "call_update")
                put("agentCode", callData.agentCode)
                put("agentName", callData.agentName)
                put("phoneNumber", callData.phoneNumber)
                put("contactName", callData.contactName ?: JSONObject.NULL)
                put("callType", callData.callType)
                put("talkDuration", callData.talkDuration)
                put("totalDuration", callData.totalDuration)
                put("callDate", callData.callDate)
                put("startTime", callData.startTime)
                put("endTime", callData.endTime)
                put("timestamp", callData.timestamp)
                put("dataSource", callData.dataSource)
            }

            socket?.emit("call_update", callUpdateData)
            Log.d(TAG, "📤 Sent call update via WebSocket: ${callData.agentCode} -> ${callData.phoneNumber}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending call update: ${e.message}")
        }
    }

    // REPLACE the existing sendTalkTimeUpdate method with this:
    suspend fun sendTalkTimeUpdate(agentCode: String, agentName: String, talkTime: Int) {
        if (!isConnected) {
            Log.w(TAG, "⚠️ Not connected, cannot send talktime_update")
            return
        }

        try {
            val talkTimeData = JSONObject().apply {
                put("action", "talktime_update")
                put("agentCode", agentCode)
                put("agentName", agentName)
                put("talkTime", talkTime)
                put("timestamp", System.currentTimeMillis())
            }

            socket?.emit("talktime_update", talkTimeData)
            Log.d(TAG, "📤 Sent talk time update via WebSocket: $agentCode -> ${talkTime}s")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending talk time update: ${e.message}")
        }
    }

    // ADD this helper method to your WebSocketManager class (optional approach)
    private fun sendMessage(data: Map<String, Any>) {
        if (!isConnected) {
            Log.w(TAG, "⚠️ Not connected, cannot send message")
            return
        }

        try {
            val jsonData = JSONObject()
            for ((key, value) in data) {
                jsonData.put(key, value ?: JSONObject.NULL)
            }

            val action = data["action"] as? String ?: "unknown"
            socket?.emit(action, jsonData)
            Log.d(TAG, "📤 Sent message via WebSocket: $action")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending message: ${e.message}")
        }
    }


}