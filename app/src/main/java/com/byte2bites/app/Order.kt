package com.byte2bites.app

data class Order(
    val orderId: String = "",
    val buyerUid: String = "",
    val totalCents: Long = 0L,
    val address: Address? = null,
    val timestamp: Long = 0L,
    val items: List<CartItem> = emptyList(),
    val deliveryFeeCents: Long = 0L,

    // NEW: how the order will be delivered
    // "DELIVERY" or "PICKUP"
    val deliveryType: String = "DELIVERY",

    // NEW: status/state in the lifecycle
    // "WAITING_APPROVAL" -> "PREPARING" -> "DELIVERING" / "READY_FOR_PICKUP" -> "DELIVERED"
    val status: String = "WAITING_APPROVAL"
)
