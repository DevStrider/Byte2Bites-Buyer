package com.byte2bites.app

data class Order(
    val orderId: String = "",
    val buyerUid: String = "",
    val totalCents: Long = 0L,
    val address: Address? = null,
    val timestamp: Long = 0L,
    val items: List<CartItem> = emptyList(),
    val deliveryFeeCents: Long = 0L,
    val deliveryType: String = "DELIVERY",

    // THIS is what we will use now:
    val status: String = "WAITING_APPROVAL",

    // VoIP info
    val buyerIp: String? = null,
    val buyerPort: Int? = null,
    val sellerIp: String? = null,
    val sellerPort: Int? = null
)

