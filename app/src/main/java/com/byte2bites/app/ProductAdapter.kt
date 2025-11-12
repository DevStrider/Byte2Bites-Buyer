package com.byte2bites.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.byte2bites.app.databinding.ItemProductBinding

class ProductAdapter(
    private val data: MutableList<Product>,
    private val onClick: (Product) -> Unit
) : RecyclerView.Adapter<ProductAdapter.VH>() {

    inner class VH(val b: ItemProductBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(p: Product) {
            b.tvName.text = p.name ?: ""
            b.tvPrice.text = p.price ?: ""

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

    fun submit(list: List<Product>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }
}
