package com.example.callanalytics.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import com.example.callanalytics.MainActivity
import com.example.callanalytics.R
import com.example.callanalytics.utils.WebSocketManager

class PersistentService : Service() {

    private lateinit var webSocketManager: WebSocketManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    companion object {
        private const val TAG = "PersistentService"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "PersistentServiceChannel"
    }

    override fun onCreate() {
        super.onCreate()
        webSocketManager = WebSocketManager.getInstance(this)
        createNotificationChannel()
        Log.d(TAG, "🚀 PersistentService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()

        // Simple WebSocket connection check
        serviceScope.launch {
            delay(5000) // Wait 5 seconds before connecting
            if (!webSocketManager.isConnected()) {
                webSocketManager.connect()
            }
        }

        Log.d(TAG, "📱 PersistentService started")
        return START_STICKY // Restart if killed
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Call Analytics - Background")
            .setContentText("Running in background")
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
                "Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps app running in background"
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
        Log.d(TAG, "🛑 PersistentService destroyed")
    }
}