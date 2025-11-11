package com.byte2bites.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.byte2bites.app.databinding.ItemSellerBinding

class SellerAdapter(
    private val data: MutableList<Seller>,
    private val onClick: (Seller) -> Unit
) : RecyclerView.Adapter<SellerAdapter.VH>() {

    inner class VH(val b: ItemSellerBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(seller: Seller) {
            b.tvName.text = seller.name ?: "Restaurant"
            b.tvPhone.text = seller.phone ?: ""
            b.root.setOnClickListener { onClick(seller) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemSellerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(data[position])
    override fun getItemCount(): Int = data.size

    fun submit(list: List<Seller>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }
}
