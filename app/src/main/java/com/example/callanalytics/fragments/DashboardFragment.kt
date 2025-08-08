package com.example.callanalytics.fragments

import android.app.DatePickerDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.callanalytics.R
import com.example.callanalytics.database.AppDatabase
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class DashboardFragment : Fragment() {

    // UI Elements
    private lateinit var tvAgentInfo: TextView
    private lateinit var tvTotalTalkTime: TextView
    private lateinit var tvAnalyticsDate: TextView
    private lateinit var btnToday: Button
    private lateinit var btnPickDate: Button

    // Call Statistics
    private lateinit var tvTotalCalls: TextView
    private lateinit var tvTotalCallDuration: TextView
    private lateinit var tvIncomingCalls: TextView
    private lateinit var tvIncomingTalkTime: TextView
    private lateinit var tvOutgoingCalls: TextView
    private lateinit var tvOutgoingTalkTime: TextView
    private lateinit var tvMissedCalls: TextView

    // Productivity Metrics
    private lateinit var tvAverageCallDuration: TextView
    private lateinit var tvAnswerRate: TextView

    private lateinit var database: AppDatabase
    private var selectedDate: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupClickListeners()
        loadAgentInfo()

        // Set today as default
        setTodayDate()
        loadAnalyticsForDate(selectedDate)
    }

    override fun onResume() {
        super.onResume()
        loadAnalyticsForDate(selectedDate)
    }

    private fun initViews(view: View) {
        // Agent Info
        tvAgentInfo = view.findViewById(R.id.tvAgentInfo)
        tvTotalTalkTime = view.findViewById(R.id.tvTotalTalkTime)
        tvAnalyticsDate = view.findViewById(R.id.tvAnalyticsDate)

        // Buttons
        btnToday = view.findViewById(R.id.btnToday)
        btnPickDate = view.findViewById(R.id.btnPickDate)

        // Call Statistics
        tvTotalCalls = view.findViewById(R.id.tvTotalCalls)
        tvTotalCallDuration = view.findViewById(R.id.tvTotalCallDuration)
        tvIncomingCalls = view.findViewById(R.id.tvIncomingCalls)
        tvIncomingTalkTime = view.findViewById(R.id.tvIncomingTalkTime)
        tvOutgoingCalls = view.findViewById(R.id.tvOutgoingCalls)
        tvOutgoingTalkTime = view.findViewById(R.id.tvOutgoingTalkTime)
        tvMissedCalls = view.findViewById(R.id.tvMissedCalls)

        // Productivity Metrics
        tvAverageCallDuration = view.findViewById(R.id.tvAverageCallDuration)
        tvAnswerRate = view.findViewById(R.id.tvAnswerRate)

        database = AppDatabase.getDatabase(requireContext())
    }

    private fun setupClickListeners() {
        btnToday.setOnClickListener {
            setTodayDate()
            updateButtonStates(true)
            loadAnalyticsForDate(selectedDate)
        }

        btnPickDate.setOnClickListener {
            showDatePicker()
        }
    }

    private fun loadAgentInfo() {
        val sharedPreferences = requireContext().getSharedPreferences("CallAnalytics", Context.MODE_PRIVATE)
        val agentCode = sharedPreferences.getString("agentCode", "Agent1") ?: "Agent1"
        val agentName = sharedPreferences.getString("agentName", "Not Set") ?: "Not Set"

        tvAgentInfo.text = "$agentCode - ($agentName)"
    }

    private fun setTodayDate() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        selectedDate = today
        updateDateDisplay()
        updateButtonStates(true)
    }

    private fun showDatePicker() {
        val calendar = Calendar.getInstance()

        // Parse current selected date if available
        if (selectedDate.isNotEmpty()) {
            try {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val date = dateFormat.parse(selectedDate)
                if (date != null) {
                    calendar.time = date
                }
            } catch (e: Exception) {
                // Use current date if parsing fails
            }
        }

        val datePickerDialog = DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val calendar = Calendar.getInstance()
                calendar.set(year, month, dayOfMonth)
                selectedDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)
                updateDateDisplay()
                updateButtonStates(false)
                loadAnalyticsForDate(selectedDate)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        datePickerDialog.show()
    }

    private fun updateDateDisplay() {
        try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val outputFormat = SimpleDateFormat("d MMMM", Locale.getDefault())
            val date = inputFormat.parse(selectedDate)

            if (date != null) {
                val formattedDate = outputFormat.format(date)
                tvAnalyticsDate.text = "Analytics for $formattedDate"
            }
        } catch (e: Exception) {
            tvAnalyticsDate.text = "Analytics for $selectedDate"
        }
    }

    private fun updateButtonStates(isTodaySelected: Boolean) {
        if (isTodaySelected) {
            btnToday.setBackgroundColor(resources.getColor(android.R.color.holo_green_dark, null))
            btnPickDate.setBackgroundColor(resources.getColor(android.R.color.holo_orange_dark, null))
        } else {
            btnToday.setBackgroundColor(resources.getColor(android.R.color.darker_gray, null))
            btnPickDate.setBackgroundColor(resources.getColor(android.R.color.holo_orange_dark, null))
        }
    }

    private fun loadAnalyticsForDate(date: String) {
        lifecycleScope.launch {
            try {
                val calls = database.callDao().getAnalyticsForDate(date)

                if (calls.isNotEmpty()) {
                    displayAnalytics(calls)
                } else {
                    displayEmptyAnalytics()
                }

            } catch (e: Exception) {
                displayErrorAnalytics()
            }
        }
    }

    private fun displayAnalytics(calls: List<com.example.callanalytics.models.CallData>) {
        // Calculate basic statistics
        val totalCalls = calls.size
        val totalTalkTime = calls.sumOf { it.talkDuration }

        // Filter by call types
        val incomingCalls = calls.filter { it.callType == "incoming" }
        val outgoingCalls = calls.filter { it.callType == "outgoing" }
        val missedCalls = calls.filter { it.callType == "missed" }

        val incomingTalkTime = incomingCalls.sumOf { it.talkDuration }
        val outgoingTalkTime = outgoingCalls.sumOf { it.talkDuration }

        // Calculate productivity metrics
        val answeredCalls = incomingCalls.size + outgoingCalls.size
        val averageCallDuration = if (answeredCalls > 0) totalTalkTime / answeredCalls else 0L
        val answerRate = if (totalCalls > 0) {
            ((answeredCalls.toDouble() / totalCalls.toDouble()) * 100)
        } else 0.0

        // Update UI
        tvTotalTalkTime.text = formatDuration(totalTalkTime)

        // Total Calls
        tvTotalCalls.text = totalCalls.toString()
        tvTotalCallDuration.text = "Call Duration: ${formatDuration(totalTalkTime)}"

        // Incoming Calls
        tvIncomingCalls.text = incomingCalls.size.toString()
        tvIncomingTalkTime.text = "Talk Time: ${formatDuration(incomingTalkTime)}"

        // Outgoing Calls
        tvOutgoingCalls.text = outgoingCalls.size.toString()
        tvOutgoingTalkTime.text = "Talk Time: ${formatDuration(outgoingTalkTime)}"

        // Missed Calls
        tvMissedCalls.text = missedCalls.size.toString()

        // Productivity Metrics
        tvAverageCallDuration.text = "- Average call duration: ${formatDuration(averageCallDuration)}"
        tvAnswerRate.text = "- Answer rate: ${String.format("%.1f", answerRate)}%"
    }

    private fun displayEmptyAnalytics() {
        tvTotalTalkTime.text = "0s"

        tvTotalCalls.text = "0"
        tvTotalCallDuration.text = "Call Duration: 0s"

        tvIncomingCalls.text = "0"
        tvIncomingTalkTime.text = "Talk Time: 0s"

        tvOutgoingCalls.text = "0"
        tvOutgoingTalkTime.text = "Talk Time: 0s"

        tvMissedCalls.text = "0"

        tvAverageCallDuration.text = "- Average call duration: 0s"
        tvAnswerRate.text = "- Answer rate: 0.0%"
    }

    private fun displayErrorAnalytics() {
        tvTotalTalkTime.text = "Error"

        tvTotalCalls.text = "Error"
        tvTotalCallDuration.text = "Error loading data"

        tvIncomingCalls.text = "Error"
        tvIncomingTalkTime.text = "Error loading data"

        tvOutgoingCalls.text = "Error"
        tvOutgoingTalkTime.text = "Error loading data"

        tvMissedCalls.text = "Error"

        tvAverageCallDuration.text = "- Error loading data"
        tvAnswerRate.text = "- Error loading data"
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainingSeconds = seconds % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m ${remainingSeconds}s"
            minutes > 0 -> "${minutes}m ${remainingSeconds}s"
            else -> "${remainingSeconds}s"
        }
    }

    private fun isToday(date: String): Boolean {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return date == today
    }
}