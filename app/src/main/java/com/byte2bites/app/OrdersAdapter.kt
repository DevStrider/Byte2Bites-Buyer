package com.byte2bites.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.byte2bites.app.databinding.ItemOrderBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OrdersAdapter(
    private val items: MutableList<Order>,
    private val onCallClicked: (Order) -> Unit
) : RecyclerView.Adapter<OrdersAdapter.VH>() {

    inner class VH(val b: ItemOrderBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemOrderBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val order = items[position]
        val b = holder.b

        val timestamp = order.timestamp
        val now = System.currentTimeMillis()
        val ageSeconds = if (timestamp > 0L) {
            ((now - timestamp) / 1000).toInt().coerceAtLeast(0)
        } else {
            0
        }

        // Simple human-readable status for the card (matches previous logic)
        val status = when {
            ageSeconds < 20 -> "Accepted"
            ageSeconds < 40 -> "Preparing"
            ageSeconds < 60 -> "Delivering"
            else -> "Order complete"
        }

        b.tvOrderId.text = "Order #${order.orderId.takeLast(6)}"
        b.tvStatus.text = status

        // While active → relative time; when complete → show date+time
        b.tvTime.text = if (status == "Order complete") {
            formatDate(timestamp)
        } else {
            formatAge(ageSeconds)
        }

        // Items summary, e.g. "2 × Burger\n1 × Fries"
        val itemsSummary = if (order.items.isNullOrEmpty()) {
            "No items"
        } else {
            order.items.joinToString(separator = "\n") { item ->
                val qty = item.quantity
                val name = item.name.ifBlank { "Item" }
                "$qty × $name"
            }
        }
        b.tvItems.text = itemsSummary

        // Total
        b.tvTotal.text = "Total: ${formatCurrency(order.totalCents)}"

        // Call restaurant
        b.btnCallRestaurant.setOnClickListener {
            onCallClicked(order)
        }
    }

    override fun getItemCount(): Int = items.size

    fun submit(newItems: List<Order>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    private fun formatAge(ageSeconds: Int): String =
        when {
            ageSeconds <= 0 -> "Just now"
            ageSeconds < 60 -> "${ageSeconds}s ago"
            else -> "${ageSeconds / 60} min ago"
        }

    private fun formatDate(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    private fun formatCurrency(cents: Long): String {
        val whole = cents / 100
        val frac = (cents % 100).toString().padStart(2, '0')
        return "$$whole.$frac"
    }
}
