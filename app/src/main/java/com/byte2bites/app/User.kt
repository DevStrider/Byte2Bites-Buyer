package com.byte2bites.app

data class User(
    val fullName: String? = null,
    val email: String? = null,
    val phoneNumber: String? = null,
    var photoUrl: String? = null,
    var points: Long = 0, // Add points field
    var credit: Long = 0  // Add credit field (in cents)
)