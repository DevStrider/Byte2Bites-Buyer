package com.byte2bites.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.byte2bites.app.databinding.ItemSellerBinding

/**
 * RecyclerView adapter responsible for displaying a list of Seller items.
 *
 * Each row shows:
 * - Seller name.
 * - Seller phone.
 * - Seller logo/profile image (loaded via Glide).
 *
 * @param data     Mutable list backing the adapter's content.
 * @param onClick  Callback invoked when a specific seller item is clicked.
 */
class SellerAdapter(
    private val data: MutableList<Seller>,
    private val onClick: (Seller) -> Unit
) : RecyclerView.Adapter<SellerAdapter.VH>() {

    /**
     * ViewHolder wrapping the view binding for a single seller item.
     */
    inner class VH(val b: ItemSellerBinding) : RecyclerView.ViewHolder(b.root) {
        /**
         * Binds a Seller object to the UI elements in the row.
         */
        fun bind(seller: Seller) {
            b.tvName.text = seller.name ?: "Restaurant"
            b.tvPhone.text = seller.phone ?: ""

            val url = seller.profileImageUrl
            if (!url.isNullOrEmpty()) {
                // Load seller image from URL; fall back to placeholder if it fails.
                Glide.with(b.ivLogo.context)
                    .load(url)
                    .placeholder(R.drawable.ic_profile_placeholder)
                    .into(b.ivLogo)
            } else {
                // No URL -> use a default placeholder icon.
                b.ivLogo.setImageResource(R.drawable.ic_profile_placeholder)
            }

            // Handle click events on the whole row.
            b.root.setOnClickListener { onClick(seller) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemSellerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(data[position])

    override fun getItemCount(): Int = data.size

    /**
     * Replaces the adapter's internal data list with a new list and refreshes the UI.
     */
    fun submit(list: List<Seller>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }
}
