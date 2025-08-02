package com.example.callanalytics.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.callanalytics.R
import com.example.callanalytics.models.CallData
import java.text.SimpleDateFormat
import java.util.*

class CallLogAdapter : ListAdapter<CallData, CallLogAdapter.CallViewHolder>(CallDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CallViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_call_log, parent, false)
        return CallViewHolder(view)
    }

    override fun onBindViewHolder(holder: CallViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CallViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvContactName: TextView = itemView.findViewById(R.id.tvContactName)
        private val tvPhoneNumber: TextView = itemView.findViewById(R.id.tvPhoneNumber)
        private val tvCallDetails: TextView = itemView.findViewById(R.id.tvCallDetails)
        private val tvWebhookStatus: TextView = itemView.findViewById(R.id.tvWebhookStatus)

        fun bind(call: CallData) {
            // Contact name or phone number
            tvContactName.text = call.contactName ?: "Unknown Number"
            tvPhoneNumber.text = formatPhoneNumber(call.phoneNumber)

            // Call details with emoji
            val typeIcon = when (call.callType) {
                "incoming" -> "📞"
                "outgoing" -> "📱"
                "missed" -> "❌"
                else -> "📞"
            }

            val callDetails = "$typeIcon ${call.callType.capitalize()} • ${formatDuration(call.talkDuration)} • ${call.startTime} • ${formatDate(call.callDate)}"
            tvCallDetails.text = callDetails

            // Webhook status
            tvWebhookStatus.text = if (call.webhookSent) {
                "✅ Webhook Sent"
            } else if (call.retryCount > 0) {
                "🔄 Retrying (${call.retryCount})"
            } else {
                "⏳ Pending"
            }

            // Set webhook status color
            val color = when {
                call.webhookSent -> android.graphics.Color.parseColor("#4CAF50")
                call.retryCount > 0 -> android.graphics.Color.parseColor("#FF9800")
                else -> android.graphics.Color.parseColor("#757575")
            }
            tvWebhookStatus.setTextColor(color)
        }

        private fun formatPhoneNumber(phoneNumber: String): String {
            return if (phoneNumber.startsWith("+91") && phoneNumber.length == 13) {
                "${phoneNumber.substring(0, 3)} ${phoneNumber.substring(3, 8)} ${phoneNumber.substring(8)}"
            } else if (phoneNumber.length == 10) {
                "${phoneNumber.substring(0, 5)} ${phoneNumber.substring(5)}"
            } else {
                phoneNumber
            }
        }

        private fun formatDuration(seconds: Long): String {
            val minutes = seconds / 60
            val remainingSeconds = seconds % 60
            return if (minutes > 0) {
                "${minutes}m ${remainingSeconds}s"
            } else {
                "${remainingSeconds}s"
            }
        }

        private fun formatDate(dateStr: String): String {
            return try {
                val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val date = inputFormat.parse(dateStr) ?: return dateStr

                val today = Calendar.getInstance()
                val yesterday = Calendar.getInstance()
                yesterday.add(Calendar.DAY_OF_MONTH, -1)

                val callDate = Calendar.getInstance()
                callDate.time = date

                when {
                    isSameDay(callDate, today) -> "Today"
                    isSameDay(callDate, yesterday) -> "Yesterday"
                    else -> {
                        val shortFormat = SimpleDateFormat("MMM d", Locale.getDefault())
                        shortFormat.format(date)
                    }
                }
            } catch (e: Exception) {
                dateStr
            }
        }

        private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
            return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                    cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
        }
    }

    class CallDiffCallback : DiffUtil.ItemCallback<CallData>() {
        override fun areItemsTheSame(oldItem: CallData, newItem: CallData): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: CallData, newItem: CallData): Boolean {
            return oldItem == newItem
        }
    }
}