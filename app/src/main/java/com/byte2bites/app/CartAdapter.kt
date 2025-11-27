package com.byte2bites.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.byte2bites.app.databinding.ItemCartBinding

/**
 * RecyclerView adapter for items in the cart.
 *
 * Each row shows:
 * - Product thumbnail image
 * - Product name
 * - Unit price
 * - Current quantity
 *
 * Plus/minus buttons call back into the activity via onInc / onDec.
 */
class CartAdapter(
    private val items: MutableList<CartItem>,
    private val onInc: (CartItem) -> Unit,
    private val onDec: (CartItem) -> Unit
) : RecyclerView.Adapter<CartAdapter.VH>() {

    /**
     * ViewHolder class that holds view binding for a single cart item row.
     */
    inner class VH(val b: ItemCartBinding) : RecyclerView.ViewHolder(b.root) {
        /**
         * Binds a CartItem to the row's UI elements.
         */
        fun bind(item: CartItem) {
            val ctx = b.root.context
            val symbol = ctx.getString(R.string.currency_symbol)
            b.tvName.text = item.name
            b.tvPrice.text = "$symbol${item.price}"
            b.tvQty.text = "Qty: ${item.quantity}"

            // Load image from URL (if any) with a placeholder fallback.
            Glide.with(b.ivThumb)
                .load(item.imageUrl)
                .placeholder(R.drawable.ic_profile_placeholder)
                .into(b.ivThumb)

            // Delegate quantity changes to the callbacks provided by CartActivity.
            b.btnPlus.setOnClickListener { onInc(item) }
            b.btnMinus.setOnClickListener { onDec(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemCartBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    /**
     * Replaces the adapter's data with a new list and refreshes the UI.
     */
    fun submit(list: List<CartItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }
}
