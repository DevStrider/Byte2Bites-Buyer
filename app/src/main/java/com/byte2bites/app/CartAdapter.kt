package com.byte2bites.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.byte2bites.app.databinding.ItemCartBinding

class CartAdapter(
    private val items: MutableList<CartItem>,
    private val onInc: (CartItem) -> Unit,
    private val onDec: (CartItem) -> Unit
) : RecyclerView.Adapter<CartAdapter.VH>() {

    inner class VH(val b: ItemCartBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: CartItem) {
            val ctx = b.root.context
            val symbol = ctx.getString(R.string.currency_symbol)
            b.tvName.text = item.name
            b.tvPrice.text = "$symbol${item.price}"
            b.tvQty.text = "Qty: ${item.quantity}"
            Glide.with(b.ivThumb)
                .load(item.imageUrl)
                .placeholder(R.drawable.ic_profile_placeholder)
                .into(b.ivThumb)

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

    fun submit(list: List<CartItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }
}
