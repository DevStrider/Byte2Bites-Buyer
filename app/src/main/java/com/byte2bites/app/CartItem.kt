package com.byte2bites.app

/**
 * Data class representing a single product entry in the buyer's cart.
 *
 * Fields:
 * @param productID  Unique id of the product under the seller.
 * @param name       Product name as displayed to the user.
 * @param price      Unit price as String (parsed into cents elsewhere).
 * @param imageUrl   Product thumbnail image URL.
 * @param quantity   Number of units in the cart.
 * @param sellerUid  UID of the seller owning this product (used for cartMeta and orders).
 */
data class CartItem(
    val productID: String = "",
    val name: String = "",
    val price: String = "",             // keep as String to match DB; parse when needed
    val imageUrl: String = "",
    val quantity: Int = 1,
    val sellerUid: String = ""
)
