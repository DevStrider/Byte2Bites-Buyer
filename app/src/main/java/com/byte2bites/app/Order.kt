package com.byte2bites.app

/**
 * Data class representing an order placed by a buyer.
 *
 * Stored in Firebase both under:
 * - /Buyers/{buyerUid}/orders/{orderId}
 * - /Sellers/{sellerUid}/orders/{orderId}
 *
 * Monetary amounts are stored as cents (integer) to avoid floating point issues.
 *
 * @param orderId           Unique ID for this order (key in the database).
 * @param buyerUid          UID of the buyer who placed this order.
 * @param totalCents        Total order value in cents (items + delivery).
 * @param address           Delivery address (if delivery type is DELIVERY).
 * @param timestamp         Time of order creation (milliseconds since epoch).
 * @param items             List of cart items included in this order.
 * @param deliveryFeeCents  Delivery fee (in cents).
 * @param deliveryType      Either "DELIVERY" or "PICKUP".
 * @param status            Current state of the order (WAITING_APPROVAL, PREPARING, etc.).
 * @param buyerIp           Buyer's IP for VoIP call (optional).
 * @param buyerPort         Buyer's UDP port for VoIP (optional).
 * @param sellerIp          Seller's IP for VoIP call (optional).
 * @param sellerPort        Seller's UDP port for VoIP (optional).
 */
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
