package com.byte2bites.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.byte2bites.app.databinding.ItemOrderBinding
import java.text.SimpleDateFormat
import java.util.*

class OrdersAdapter(
    private val items: MutableList<Order>
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

        // Read status directly from buyer node (no transformations)
        val status = order.status ?: "UNKNOWN"

        // Display the raw status text
        b.tvOrderId.text = "Order #${order.orderId.takeLast(6)}"
        b.tvStatus.text = status

        // Always show the actual order timestamp
        b.tvTime.text = formatDate(timestamp)

        // Items summary (e.g. "2 × Burger\n1 × Fries")
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
    }

    override fun getItemCount(): Int = items.size

    fun submit(newItems: List<Order>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
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