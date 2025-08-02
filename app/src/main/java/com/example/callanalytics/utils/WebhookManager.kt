package com.example.callanalytics.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.callanalytics.database.AppDatabase
import com.example.callanalytics.models.CallData
import com.example.callanalytics.models.FailedWebhook
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class WebhookManager(private val context: Context) {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("CallAnalytics", Context.MODE_PRIVATE)
    private val database = AppDatabase.getDatabase(context)

    companion object {
        private const val TAG = "WebhookManager"
        private const val DEFAULT_WEBHOOK_URL = "https://flow.tsblive.in/webhook/51e9733d-1add-487f-8fde-a744f63d806b"
        private const val RETRY_DELAY_MS = 30000L // 30 seconds
        private const val CONNECTION_TIMEOUT = 15000
        private const val READ_TIMEOUT = 15000
    }

    fun sendWebhook(callData: CallData) {
        CoroutineScope(Dispatchers.IO).launch {
            val webhookUrl = sharedPreferences.getString("webhookUrl", DEFAULT_WEBHOOK_URL) ?: DEFAULT_WEBHOOK_URL

            val success = sendWebhookRequest(callData, webhookUrl)

            if (success) {
                database.callDao().updateCall(callData.copy(webhookSent = true))
                Log.d(TAG, "✅ Webhook sent successfully for call ${callData.id}")
            } else {
                // Auto retry once
                Log.w(TAG, "🔄 Webhook failed, auto-retrying in 30 seconds...")
                delay(RETRY_DELAY_MS)

                val retrySuccess = sendWebhookRequest(callData, webhookUrl)

                if (retrySuccess) {
                    database.callDao().updateCall(callData.copy(webhookSent = true, retryCount = 1))
                    Log.d(TAG, "✅ Webhook retry successful for call ${callData.id}")
                } else {
                    // Save for manual retry
                    val failedWebhook = FailedWebhook(
                        callDataJson = callData.toWebhookJson(),
                        retryCount = 1
                    )
                    database.callDao().insertFailedWebhook(failedWebhook)
                    database.callDao().updateCall(callData.copy(retryCount = 2))
                    Log.e(TAG, "❌ Webhook failed permanently for call ${callData.id}")
                }
            }
        }
    }

    private suspend fun sendWebhookRequest(callData: CallData, webhookUrl: String): Boolean {
        return try {
            val url = URL(webhookUrl)
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "CallAnalytics/1.0")
                doOutput = true
                connectTimeout = CONNECTION_TIMEOUT
                readTimeout = READ_TIMEOUT
            }

            val jsonData = callData.toWebhookJson()
            Log.d(TAG, "📤 Sending webhook: $jsonData")

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonData)
                writer.flush()
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "📥 Webhook response code: $responseCode")

            if (responseCode in 200..299) {
                val response = connection.inputStream.bufferedReader().readText()
                Log.d(TAG, "📥 Webhook response: $response")
                true
            } else {
                val errorResponse = connection.errorStream?.bufferedReader()?.readText() ?: "No error details"
                Log.e(TAG, "❌ Webhook error $responseCode: $errorResponse")
                false
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Webhook network error", e)
            false
        }
    }

    fun retryFailedWebhooks() {
        CoroutineScope(Dispatchers.IO).launch {
            val failedWebhooks = database.callDao().getFailedWebhooks()
            val webhookUrl = sharedPreferences.getString("webhookUrl", DEFAULT_WEBHOOK_URL) ?: DEFAULT_WEBHOOK_URL

            Log.d(TAG, "🔄 Retrying ${failedWebhooks.size} failed webhooks")

            for (webhook in failedWebhooks) {
                try {
                    val success = sendRawWebhook(webhook.callDataJson, webhookUrl)

                    if (success) {
                        database.callDao().deleteFailedWebhook(webhook)
                        Log.d(TAG, "✅ Manual retry successful")
                    }

                    delay(1000) // 1 second between retries

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error in manual retry", e)
                }
            }
        }
    }

    private suspend fun sendRawWebhook(jsonData: String, webhookUrl: String): Boolean {
        return try {
            val url = URL(webhookUrl)
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = CONNECTION_TIMEOUT
                readTimeout = READ_TIMEOUT
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonData)
                writer.flush()
            }

            connection.responseCode in 200..299

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending raw webhook", e)
            false
        }
    }

    suspend fun getFailedWebhookCount(): Int {
        return database.callDao().getFailedWebhooks().size
    }
}