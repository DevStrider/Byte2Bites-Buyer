package com.byte2bites.app

data class CartItem(
    val productID: String = "",
    val name: String = "",
    val price: String = "",             // keep as String to match DB; parse when needed
    val imageUrl: String = "",
    val quantity: Int = 1,
    val sellerUid: String = ""
)
