package com.byte2bites.app

data class Address(
    val buildingName: String? = null,
    val streetName: String? = null,
    val apartmentNumber: String? = null,
    val floorNumber: String? = null,

    // Hidden from user, filled from map picker
    val latitude: Double? = null,
    val longitude: Double? = null
)
