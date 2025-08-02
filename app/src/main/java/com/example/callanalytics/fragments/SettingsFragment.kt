package com.example.callanalytics.fragments

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.callanalytics.R
import com.example.callanalytics.utils.WebhookManager
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private lateinit var spinnerAgent: Spinner
    private lateinit var etAgentName: EditText
    private lateinit var etWebhookUrl: EditText
    private lateinit var btnSaveSettings: Button
    private lateinit var btnRetryWebhooks: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvFailedWebhooks: TextView

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var webhookManager: WebhookManager

    companion object {
        const val MAX_AGENTS = 25
        const val DEFAULT_WEBHOOK_URL = "https://flow.tsblive.in/webhook/51e9733d-1add-487f-8fde-a744f63d806b"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupAgentSpinner()
        loadSettings()
        setupClickListeners()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateFailedWebhookCount()
    }

    private fun initViews(view: View) {
        spinnerAgent = view.findViewById(R.id.spinnerAgent)
        etAgentName = view.findViewById(R.id.etAgentName)
        etWebhookUrl = view.findViewById(R.id.etWebhookUrl)
        btnSaveSettings = view.findViewById(R.id.btnSaveSettings)
        btnRetryWebhooks = view.findViewById(R.id.btnRetryWebhooks)
        tvStatus = view.findViewById(R.id.tvStatus)
        tvFailedWebhooks = view.findViewById(R.id.tvFailedWebhooks)

        sharedPreferences = requireContext().getSharedPreferences("CallAnalytics", Context.MODE_PRIVATE)
        webhookManager = WebhookManager(requireContext())
    }

    private fun setupAgentSpinner() {
        val agentList = (1..MAX_AGENTS).map { "Agent$it" }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, agentList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerAgent.adapter = adapter
    }

    private fun loadSettings() {
        val savedAgentCode = sharedPreferences.getString("agentCode", "Agent1") ?: "Agent1"
        val savedAgentName = sharedPreferences.getString("agentName", "") ?: ""
        val savedWebhookUrl = sharedPreferences.getString("webhookUrl", DEFAULT_WEBHOOK_URL) ?: DEFAULT_WEBHOOK_URL

        val adapter = spinnerAgent.adapter as ArrayAdapter<String>
        val position = adapter.getPosition(savedAgentCode)
        spinnerAgent.setSelection(position)

        etAgentName.setText(savedAgentName)
        etWebhookUrl.setText(savedWebhookUrl)
    }

    private fun setupClickListeners() {
        btnSaveSettings.setOnClickListener { saveSettings() }
        btnRetryWebhooks.setOnClickListener { retryFailedWebhooks() }
    }

    private fun saveSettings() {
        val agentCode = spinnerAgent.selectedItem.toString()
        val agentName = etAgentName.text.toString().trim()
        val webhookUrl = etWebhookUrl.text.toString().trim()

        if (agentName.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter agent name", Toast.LENGTH_SHORT).show()
            return
        }

        if (webhookUrl.isEmpty() || !isValidUrl(webhookUrl)) {
            Toast.makeText(requireContext(), "Please enter valid webhook URL", Toast.LENGTH_SHORT).show()
            return
        }

        sharedPreferences.edit()
            .putString("agentCode", agentCode)
            .putString("agentName", agentName)
            .putString("webhookUrl", webhookUrl)
            .apply()

        Toast.makeText(requireContext(), "✅ Settings saved successfully!", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    private fun retryFailedWebhooks() {
        lifecycleScope.launch {
            val count = webhookManager.getFailedWebhookCount()

            if (count == 0) {
                Toast.makeText(requireContext(), "No failed webhooks to retry", Toast.LENGTH_SHORT).show()
                return@launch
            }

            Toast.makeText(requireContext(), "🔄 Retrying $count failed webhooks...", Toast.LENGTH_SHORT).show()
            webhookManager.retryFailedWebhooks()
            updateFailedWebhookCount()
        }
    }

    private fun isValidUrl(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://")
    }

    private fun updateStatus() {
        val agentCode = sharedPreferences.getString("agentCode", "Not Set") ?: "Not Set"
        val agentName = sharedPreferences.getString("agentName", "Not Set") ?: "Not Set"
        val webhookUrl = sharedPreferences.getString("webhookUrl", "Not Set") ?: "Not Set"

        val status = """
            📋 CURRENT CONFIGURATION:
            Agent Code: $agentCode
            Agent Name: $agentName
            
            🔗 WEBHOOK URL:
            ${webhookUrl.take(50)}${if (webhookUrl.length > 50) "..." else ""}
            
            🎯 STATUS: ${if (agentName != "Not Set") "🚀 READY" else "⚠️ SETUP REQUIRED"}
        """.trimIndent()

        tvStatus.text = status
    }

    private fun updateFailedWebhookCount() {
        lifecycleScope.launch {
            val count = webhookManager.getFailedWebhookCount()

            if (count > 0) {
                tvFailedWebhooks.text = "❌ $count failed webhooks - Tap 'Retry Failed Webhooks' to resend"
                tvFailedWebhooks.visibility = View.VISIBLE
                btnRetryWebhooks.visibility = View.VISIBLE
            } else {
                tvFailedWebhooks.visibility = View.GONE
                btnRetryWebhooks.visibility = View.GONE
            }
        }
    }
}