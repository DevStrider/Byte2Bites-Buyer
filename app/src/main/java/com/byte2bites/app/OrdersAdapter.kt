package com.byte2bites.app

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.byte2bites.app.databinding.ItemOrderBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for displaying a list of orders in the OrdersFragment.
 *
 * Each row shows:
 * - Order short ID (# last 6 chars).
 * - Human-readable status.
 * - Relative time ("x min ago") or exact timestamp for final states.
 * - Short items summary.
 * - Total price formatted using the app's currency symbol.
 *
 * Also provides a "Call restaurant" button to start a VoIP call to the seller.
 */
class OrdersAdapter(
    private val items: MutableList<Order>,
    private val onCallClicked: (Order) -> Unit
) : RecyclerView.Adapter<OrdersAdapter.VH>() {

    /**
     * Simple ViewHolder wrapper for the item layout binding.
     */
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

        // Status text based on seller-provided status string
        val statusText = mapStatusToText(order.status, order.deliveryType)
        b.tvOrderId.text = "Order #${order.orderId.takeLast(6)}"
        b.tvStatus.text = statusText

        // Final statuses: show exact date; otherwise relative "x min ago"
        b.tvTime.text = if (isFinalStatus(order.status)) {
            formatDate(timestamp)
        } else {
            formatAge(ageSeconds)
        }

        // Items summary (2 × Burger\n1 × Fries)
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

        // ✅ Total price using currency_symbol (E£)
        b.tvTotal.text = "Total: ${formatCurrency(holder.itemView.context, order.totalCents)}"

        // Call restaurant button uses provided callback.
        b.btnCallRestaurant.setOnClickListener {
            onCallClicked(order)
        }
    }

    override fun getItemCount(): Int = items.size

    /**
     * Replace the entire adapter list and refresh the view.
     * Newest orders are sorted by the fragment before calling this.
     */
    fun submit(newItems: List<Order>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    // ---- Helpers ----

    /**
     * Maps internal status and delivery type to a user-friendly string.
     */
    private fun mapStatusToText(status: String?, deliveryType: String?): String {
        return when (status) {
            "WAITING_APPROVAL" -> "Waiting for seller approval"
            "ACCEPTED" -> "Accepted"
            "PREPARING" -> "Preparing"
            "READY_FOR_PICKUP" -> "Ready for pickup"
            "OUT_FOR_DELIVERY" ->
                if (deliveryType == "PICKUP") "Ready for pickup" else "Out for delivery"
            "DELIVERED" -> "Delivered"
            "DENIED" -> "Order denied"
            else -> status?.ifBlank { "Order placed" } ?: "Order placed"
        }
    }

    /**
     * Returns true if this status represents a "final" state where we
     * show the exact date instead of "x min ago".
     */
    private fun isFinalStatus(status: String?): Boolean {
        return status == "DELIVERED" || status == "DENIED"
    }

    /**
     * Formats an age in seconds as a relative time string ("40s ago", "3 min ago").
     */
    private fun formatAge(ageSeconds: Int): String =
        when {
            ageSeconds <= 0 -> "Just now"
            ageSeconds < 60 -> "${ageSeconds}s ago"
            else -> "${ageSeconds / 60} min ago"
        }

    /**
     * Formats a timestamp (ms) into a human-readable date string.
     */
    private fun formatDate(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    /**
     * Converts a value in cents to a localized currency string using R.string.currency_symbol.
     */
    // ✅ Uses <string name="currency_symbol">E£</string>
    private fun formatCurrency(context: Context, cents: Long): String {
        val symbol = context.getString(R.string.currency_symbol)
        val whole = cents / 100
        val frac = (cents % 100).toString().padStart(2, '0')
        return "$symbol$whole.$frac"
    }
}
