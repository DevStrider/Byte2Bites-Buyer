package com.byte2bites.app

data class Product(
    val productID: String? = null,
    val name: String? = null,
    val price: String? = null,          // stored as String in your seller data
    val description: String? = null,
    val imageUrl: String? = null,
    val sellerUid: String? = null       // we’ll attach this when reading from /Sellers
)
