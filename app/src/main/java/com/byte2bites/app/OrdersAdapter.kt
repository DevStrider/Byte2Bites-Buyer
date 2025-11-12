package com.byte2bites.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.byte2bites.app.databinding.ItemOrderBinding

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

        val now = System.currentTimeMillis()
        val ageSeconds = ((now - order.timestamp) / 1000).toInt().coerceAtLeast(0)

        val status = when {
            ageSeconds < 20 -> "Accepted"
            ageSeconds < 40 -> "Preparing"
            ageSeconds < 60 -> "Delivering"
            ageSeconds < 90 -> "Order complete"
            else -> "Order complete"
        }

        b.tvOrderId.text = "Order #${order.orderId.takeLast(6)}"
        b.tvStatus.text = status
        b.tvTime.text = formatAge(ageSeconds)
        b.tvTotal.text = formatCurrency(order.totalCents)
    }

    override fun getItemCount(): Int = items.size

    fun submit(newItems: List<Order>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    private fun formatAge(ageSeconds: Int): String =
        when {
            ageSeconds < 10 -> "Just now"
            ageSeconds < 60 -> "${ageSeconds}s ago"
            else -> "${ageSeconds / 60} min ago"
        }

    private fun formatCurrency(cents: Long): String {
        val whole = cents / 100
        val frac = (cents % 100).toString().padStart(2, '0')
        return "$$whole.$frac"
    }
}
