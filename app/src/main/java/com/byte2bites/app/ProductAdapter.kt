package com.byte2bites.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.byte2bites.app.databinding.ItemProductBinding

/**
 * RecyclerView adapter for showing products in a grid (e.g., SellerProductsActivity).
 *
 * Each product row shows:
 * - Thumbnail image
 * - Name
 * - Price with currency symbol
 *
 * Clicking an item invokes the provided onClick callback with the Product.
 */
class ProductAdapter(
    private val data: MutableList<Product>,
    private val onClick: (Product) -> Unit
) : RecyclerView.Adapter<ProductAdapter.VH>() {

    /**
     * ViewHolder holding a binding to a single product item layout.
     */
    inner class VH(val b: ItemProductBinding) : RecyclerView.ViewHolder(b.root) {
        /**
         * Binds a Product to the UI components.
         */
        fun bind(p: Product) {
            val ctx = b.root.context
            val symbol = ctx.getString(R.string.currency_symbol)

            b.tvName.text = p.name ?: ""

            // Safely handle missing price by falling back to "0".
            val priceText = p.price?.takeIf { it.isNotBlank() } ?: "0"
            b.tvPrice.text = "$symbol$priceText"

            val url = p.imageUrl
            if (!url.isNullOrEmpty()) {
                Glide.with(b.ivThumb.context)
                    .load(url)
                    .placeholder(R.drawable.ic_profile_placeholder)
                    .into(b.ivThumb)
            } else {
                b.ivThumb.setImageResource(R.drawable.ic_profile_placeholder)
            }

            b.root.setOnClickListener { onClick(p) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemProductBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(data[position])
    override fun getItemCount(): Int = data.size

    /**
     * Replaces the internal list of products and refreshes the RecyclerView.
     */
    fun submit(list: List<Product>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }
}
