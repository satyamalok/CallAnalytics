package com.example.callanalytics.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.example.callanalytics.services.CallMonitorService

class PhoneStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PhoneStateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            Log.d(TAG, "📞 Phone state changed: $state, Number: $phoneNumber")

            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    Log.d(TAG, "📲 Incoming call detected")
                    startCallMonitorService(context, "RINGING", phoneNumber)
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    Log.d(TAG, "📞 Call answered/outgoing")
                    startCallMonitorService(context, "OFFHOOK", null)
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    Log.d(TAG, "📴 Call ended")
                    startCallMonitorService(context, "IDLE", null)
                }
            }
        }
    }

    private fun startCallMonitorService(context: Context, state: String, phoneNumber: String?) {
        val serviceIntent = Intent(context, CallMonitorService::class.java).apply {
            putExtra("PHONE_STATE", state)
            putExtra("PHONE_NUMBER", phoneNumber)
        }

        try {
            // API level check for startForegroundService
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "✅ CallMonitorService started for state: $state")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start CallMonitorService", e)
        }
    }
}