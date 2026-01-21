package com.sanad.agent.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sanad.agent.R
import com.sanad.agent.model.Order

class OrdersAdapter(
    private val onOrderClick: (Order) -> Unit
) : ListAdapter<Order, OrdersAdapter.OrderViewHolder>(OrderDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_order, parent, false)
        return OrderViewHolder(view)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class OrderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val appIcon: TextView = itemView.findViewById(R.id.appIcon)
        private val appName: TextView = itemView.findViewById(R.id.appName)
        private val statusText: TextView = itemView.findViewById(R.id.statusText)
        private val statusBadge: TextView = itemView.findViewById(R.id.statusBadge)
        private val statusIndicator: View = itemView.findViewById(R.id.statusIndicator)

        fun bind(order: Order) {
            appIcon.text = getAppEmoji(order.appName)
            appName.text = order.appName
            
            val statusInfo = getStatusInfo(order)
            
            statusText.text = statusInfo.description
            statusBadge.text = statusInfo.label
            
            // Set badge background with rounded corners
            val badgeDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f
                setColor(ContextCompat.getColor(itemView.context, statusInfo.color))
            }
            statusBadge.background = badgeDrawable
            
            // Set indicator color
            val indicatorDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f
                setColor(ContextCompat.getColor(itemView.context, statusInfo.color))
            }
            statusIndicator.background = indicatorDrawable
            
            itemView.setOnClickListener { onOrderClick(order) }
        }
        
        private fun getAppEmoji(appName: String): String {
            return when {
                appName.contains("هنقر", ignoreCase = true) -> "🍔"
                appName.contains("جاهز", ignoreCase = true) -> "🍕"
                appName.contains("تويو", ignoreCase = true) -> "📦"
                appName.contains("مرسول", ignoreCase = true) -> "🛵"
                appName.contains("كريم", ignoreCase = true) -> "🚗"
                else -> "📱"
            }
        }
        
        private fun getStatusInfo(order: Order): StatusInfo {
            val hasComplaint = !order.complaintText.isNullOrEmpty()
            
            return when (order.compensationStatus) {
                "monitoring" -> StatusInfo(
                    label = "يراقب",
                    description = "سند يتابع هذا الطلب",
                    color = R.color.status_monitoring
                )
                "intervening" -> StatusInfo(
                    label = "جاري التدخل",
                    description = "تم رصد تأخير، جاري تجهيز الشكوى...",
                    color = R.color.status_intervening
                )
                "claim_ready" -> StatusInfo(
                    label = "شكوى جاهزة",
                    description = "اضغط هنا لنسخ الشكوى وإرسالها",
                    color = R.color.status_claim_ready
                )
                "awaiting_reply" -> StatusInfo(
                    label = "بانتظار الرد",
                    description = "تم إرسال الشكوى، ننتظر الرد",
                    color = R.color.status_awaiting
                )
                "escalated" -> StatusInfo(
                    label = "تم التصعيد",
                    description = "تمت إحالة الشكوى لحماية المستهلك",
                    color = R.color.status_escalated
                )
                "success" -> StatusInfo(
                    label = "تم التعويض",
                    description = "مبروك! حصلت على التعويض",
                    color = R.color.status_success
                )
                else -> {
                    if (hasComplaint) {
                        StatusInfo(
                            label = "شكوى جاهزة",
                            description = "اضغط هنا لنسخ الشكوى",
                            color = R.color.status_claim_ready
                        )
                    } else {
                        StatusInfo(
                            label = "يراقب",
                            description = "سند يتابع هذا الطلب",
                            color = R.color.status_monitoring
                        )
                    }
                }
            }
        }
    }
    
    data class StatusInfo(
        val label: String,
        val description: String,
        val color: Int
    )

    class OrderDiffCallback : DiffUtil.ItemCallback<Order>() {
        override fun areItemsTheSame(oldItem: Order, newItem: Order): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Order, newItem: Order): Boolean {
            return oldItem == newItem
        }
    }
}
