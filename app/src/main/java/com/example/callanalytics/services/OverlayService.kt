package com.example.callanalytics.services

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.TextView
import com.example.callanalytics.R

class OverlayService : Service() {

    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    companion object {
        private const val TAG = "OverlayService"
        const val ACTION_SHOW_OVERLAY = "show_overlay"
        const val ACTION_HIDE_OVERLAY = "hide_overlay"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_IDLE_TIME = "idle_time"
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.d(TAG, "🚀 OverlayService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> {
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Time to make a call!"
                val idleTime = intent.getStringExtra(EXTRA_IDLE_TIME) ?: "5 minutes"
                showOverlay(message, idleTime)
            }
            ACTION_HIDE_OVERLAY -> {
                hideOverlay()
            }
        }
        return START_NOT_STICKY
    }

    private fun showOverlay(message: String, idleTime: String) {
        try {
            // Don't show if already showing
            if (overlayView != null) {
                hideOverlay()
            }

            // Inflate the overlay layout
            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_notification, null)

            // Set up layout parameters
            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }

            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 100 // Offset from top
            }

            // Set up UI elements
            setupOverlayUI(message, idleTime)

            // Add to window manager
            windowManager?.addView(overlayView, layoutParams)

            Log.d(TAG, "✅ Overlay notification shown: $message")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing overlay", e)
        }
    }

    private fun setupOverlayUI(message: String, idleTime: String) {
        overlayView?.let { view ->
            // Set message text
            view.findViewById<TextView>(R.id.tvMessage)?.text = message
            view.findViewById<TextView>(R.id.tvIdleTime)?.text = "Idle for: $idleTime"

            // Set up close button
            view.findViewById<TextView>(R.id.tvCloseButton)?.setOnClickListener {
                hideOverlay()
            }

            // Set up action button
            view.findViewById<Button>(R.id.btnCallNow)?.setOnClickListener {
                Log.d(TAG, "📞 User acknowledged call reminder")
                hideOverlay()

                // Optional: Send acknowledgment to server
                sendAcknowledgmentToServer()
            }

            // Make overlay draggable
            makeOverlayDraggable(view)
        }
    }

    private fun makeOverlayDraggable(view: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams?.x ?: 0
                    initialY = layoutParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams?.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams?.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(overlayView, layoutParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun hideOverlay() {
        try {
            overlayView?.let { view ->
                windowManager?.removeView(view)
                overlayView = null
                Log.d(TAG, "✅ Overlay notification hidden")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error hiding overlay", e)
        }
    }

    private fun sendAcknowledgmentToServer() {
        // TODO: Send acknowledgment to server that user saw the reminder
        // This can be implemented later to track reminder effectiveness
        Log.d(TAG, "📡 Sending reminder acknowledgment to server")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        Log.d(TAG, "🛑 OverlayService destroyed")
    }
}