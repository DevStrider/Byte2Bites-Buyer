package com.byte2bites.app

/**
 * Data model representing a seller (shop / restaurant).
 * This is typically stored under /Sellers/{uid} in Firebase Realtime Database.
 *
 * @param uid              Firebase Authentication UID of the seller account.
 * @param name             Display name of the restaurant/shop.
 * @param email            Contact email for the seller.
 * @param phone            Contact phone number shown in the UI.
 * @param profileImageUrl  URL of the seller/shop image (usually stored in S3).
 */
data class Seller(
    val uid: String = "",
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val profileImageUrl: String? = null      // restaurant photo
)
