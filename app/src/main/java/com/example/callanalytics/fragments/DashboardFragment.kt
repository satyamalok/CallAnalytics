package com.example.callanalytics.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.callanalytics.R
import com.example.callanalytics.database.AppDatabase
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class DashboardFragment : Fragment() {

    private lateinit var tvStatus: TextView
    private lateinit var tvAgentInfo: TextView
    private lateinit var tvTodayStats: TextView
    private lateinit var tvRecentActivity: TextView

    private lateinit var database: AppDatabase

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
        loadDashboardData()
    }

    override fun onResume() {
        super.onResume()
        loadDashboardData()
    }

    private fun initViews(view: View) {
        tvStatus = view.findViewById(R.id.tvStatus)
        tvAgentInfo = view.findViewById(R.id.tvAgentInfo)
        tvTodayStats = view.findViewById(R.id.tvTodayStats)
        tvRecentActivity = view.findViewById(R.id.tvRecentActivity)

        database = AppDatabase.getDatabase(requireContext())
    }

    private fun loadDashboardData() {
        val sharedPreferences = requireContext().getSharedPreferences("CallAnalytics", Context.MODE_PRIVATE)
        val agentCode = sharedPreferences.getString("agentCode", "Agent1") ?: "Agent1"
        val agentName = sharedPreferences.getString("agentName", "Not Set") ?: "Not Set"

        // Update agent info
        tvAgentInfo.text = "$agentCode - $agentName"

        // Update status
        val status = if (agentName != "Not Set") {
            "🟢 ACTIVE - Call monitoring enabled"
        } else {
            "🟡 SETUP REQUIRED - Configure agent in settings"
        }
        tvStatus.text = status

        // Load today's stats
        loadTodayStats()
    }

    private fun loadTodayStats() {
        lifecycleScope.launch {
            try {
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val todayCalls = database.callDao().getAnalyticsForDate(today)

                if (todayCalls.isNotEmpty()) {
                    val totalCalls = todayCalls.size
                    val totalDuration = todayCalls.sumOf { it.talkDuration }
                    val incomingCalls = todayCalls.count { it.callType == "incoming" }
                    val outgoingCalls = todayCalls.count { it.callType == "outgoing" }
                    val missedCalls = todayCalls.count { it.callType == "missed" }

                    val statsText = """
                        📊 TODAY'S STATISTICS
                        
                        🔵 Total Calls: $totalCalls
                        🟢 Incoming: $incomingCalls
                        🟠 Outgoing: $outgoingCalls
                        🔴 Missed: $missedCalls
                        
                        ⏰ Total Talk Time: ${formatDuration(totalDuration)}
                    """.trimIndent()

                    tvTodayStats.text = statsText

                    // Show recent activity
                    val recentCall = todayCalls.maxByOrNull { it.timestamp }
                    if (recentCall != null) {
                        tvRecentActivity.text = "📞 Last Call: ${recentCall.phoneNumber} (${recentCall.callType}) - ${recentCall.startTime}"
                    } else {
                        tvRecentActivity.text = "📭 No recent activity"
                    }

                } else {
                    tvTodayStats.text = "📭 No calls today\n\nMake some calls to see analytics here!"
                    tvRecentActivity.text = "📭 No recent activity"
                }

            } catch (e: Exception) {
                tvTodayStats.text = "❌ Error loading statistics"
                tvRecentActivity.text = "❌ Error loading recent activity"
            }
        }
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
}