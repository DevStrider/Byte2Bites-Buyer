package com.byte2bites.app

/**
 * Data class representing a saved delivery address for a buyer.
 *
 * The user only sees the textual fields (building/street/apartment/floor)
 * while latitude/longitude are filled via the map picker and used for:
 * - Distance-based delivery pricing.
 * - Checking whether delivery is available to this location.
 */
data class Address(
    val buildingName: String? = null,   // Building or compound name (visible to user)
    val streetName: String? = null,     // Street name (visible to user)
    val apartmentNumber: String? = null,// Apartment/unit number (visible to user)
    val floorNumber: String? = null,    // Floor number (visible to user)

    // Hidden from user, filled from map picker
    val latitude: Double? = null,       // Latitude chosen on map
    val longitude: Double? = null       // Longitude chosen on map
)
