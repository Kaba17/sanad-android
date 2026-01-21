package com.sanad.agent.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
        private val orderId: TextView = itemView.findViewById(R.id.orderId)
        private val statusBadge: TextView = itemView.findViewById(R.id.statusBadge)
        private val statusIndicator: View = itemView.findViewById(R.id.statusIndicator)
        private val complaintPreview: TextView = itemView.findViewById(R.id.complaintPreview)

        fun bind(order: Order) {
            appIcon.text = order.appName.first().uppercase()
            appName.text = order.appName
            orderId.text = "#${order.orderId}"
            
            val (statusText, statusColor) = when (order.compensationStatus) {
                "monitoring" -> "يراقب" to R.color.status_monitoring
                "intervening" -> "جاري التدخل" to R.color.status_intervening
                "claim_ready" -> "جاهز للإرسال" to R.color.status_claim_ready
                "awaiting_reply" -> "في انتظار الرد" to R.color.status_awaiting
                "escalated" -> "تصعيد" to R.color.status_escalated
                "success" -> "تم التعويض" to R.color.status_success
                else -> "غير معروف" to R.color.status_monitoring
            }
            
            statusBadge.text = statusText
            statusBadge.setBackgroundColor(ContextCompat.getColor(itemView.context, statusColor))
            statusIndicator.setBackgroundColor(ContextCompat.getColor(itemView.context, statusColor))
            
            if (!order.complaintText.isNullOrEmpty()) {
                complaintPreview.visibility = View.VISIBLE
                complaintPreview.text = order.complaintText.take(100) + if (order.complaintText.length > 100) "..." else ""
            } else {
                complaintPreview.visibility = View.GONE
            }
            
            itemView.setOnClickListener { onOrderClick(order) }
        }
    }

    class OrderDiffCallback : DiffUtil.ItemCallback<Order>() {
        override fun areItemsTheSame(oldItem: Order, newItem: Order): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Order, newItem: Order): Boolean {
            return oldItem == newItem
        }
    }
}
