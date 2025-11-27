package com.byte2bites.app

/**
 * Data model for a buyer user.
 *
 * Fields:
 * @param fullName   User's display name.
 * @param email      User's email used for login and contact.
 * @param phoneNumber User's contact phone number.
 * @param photoUrl   URL of the profile picture stored in S3 (optional).
 * @param points     Loyalty points balance for gamification / rewards.
 * @param credit     Wallet balance in CENTS (integer), not floating-point.
 */
data class User(
    val fullName: String? = null,
    val email: String? = null,
    val phoneNumber: String? = null,
    var photoUrl: String? = null,
    var points: Long = 0, // Add points field
    var credit: Long = 0  // Add credit field (in cents)
)
