package com.byte2bites.app

data class Order(
    val orderId: String = "",
    val buyerUid: String = "",
    val totalCents: Long = 0,           // calculated total in smallest unit
    val address: Address? = null,       // from /Buyers/<uid>/address
    val timestamp: Long = System.currentTimeMillis(),
    val items: List<CartItem> = emptyList()
)
