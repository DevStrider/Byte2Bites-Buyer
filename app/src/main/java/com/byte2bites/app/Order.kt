package com.byte2bites.app

data class Order(
    val orderId: String = "",
    val buyerUid: String = "",
    val totalCents: Long = 0L,
    val address: Address? = null,
    val timestamp: Long = 0L,
    val items: List<CartItem> = emptyList(),
    val deliveryFeeCents: Long = 0L,
    val deliveryType: String = "DELIVERY", // "DELIVERY" or "PICKUP"
    val status: String? = null // This will be synced from seller node
)