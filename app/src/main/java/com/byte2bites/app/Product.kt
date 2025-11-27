package com.byte2bites.app

/**
 * Data model for a product offered by a seller.
 *
 * Fields:
 * @param productID   Unique key for this product under the seller's /products node.
 * @param name        Product name shown to buyers.
 * @param price       Product price stored as String (parsed elsewhere into cents when needed).
 * @param description Text describing the product (Optional).
 * @param imageUrl    URL of the product image (stored in S3).
 * @param sellerUid   UID of the seller who owns this product.
 */
data class Product(
    val productID: String? = null,
    val name: String? = null,
    val price: String? = null,          // stored as String in your seller data
    val description: String? = null,
    val imageUrl: String? = null,
    val sellerUid: String? = null       // we’ll attach this when reading from /Sellers
)
