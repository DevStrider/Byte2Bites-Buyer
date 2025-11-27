package com.byte2bites.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.byte2bites.app.databinding.ActivityLocationPickerBinding
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng

/**
 * Activity that allows the user to pick a location using Google Maps.
 *
 * Responsibilities:
 * - Show map centered at:
 *   - existing saved address (if provided), or
 *   - current device location, or
 *   - a default city (Cairo) as fallback.
 * - Let the user move the map and confirm the camera's center as the chosen location.
 * - Return the selected latitude/longitude back to the caller (AddressActivity).
 */
class LocationPickerActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityLocationPickerBinding
    private lateinit var map: GoogleMap

    // Fused Location Provider used to get last known device location.
    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    // Optional initial coordinates passed in when user is editing an existing address.
    private var initialLat: Double? = null
    private var initialLng: Double? = null

    /**
     * ActivityResult launcher for requesting location permissions at runtime.
     * If granted -> enableMyLocation(), otherwise -> fallback to default camera position.
     */
    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) {
                enableMyLocation()
            } else {
                Toast.makeText(
                    this,
                    "Location permission is required to pick your address.",
                    Toast.LENGTH_LONG
                ).show()
                moveCameraToDefault()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocationPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Read initial coordinates (if any) from Intent extras.
        // If Double.NaN is passed, we ignore it and treat as "no initial location".
        initialLat = intent.getDoubleExtra("initial_lat", Double.NaN)
            .takeIf { !it.isNaN() }
        initialLng = intent.getDoubleExtra("initial_lng", Double.NaN)
            .takeIf { !it.isNaN() }

        // Obtain the SupportMapFragment and request the map asynchronously.
        val mapFragment =
            supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Back icon -> close and return without result.
        binding.ivBack.setOnClickListener { finish() }

        // Confirm button -> return current camera center as selected location.
        binding.btnConfirmLocation.setOnClickListener {
            if (!::map.isInitialized) return@setOnClickListener
            val center: LatLng = map.cameraPosition.target

            // We reuse the same Intent, adding result extras.
            val data = intent.apply {
                putExtra("lat", center.latitude)
                putExtra("lng", center.longitude)
            }
            setResult(Activity.RESULT_OK, data)
            finish()
        }
    }

    /**
     * Called when the GoogleMap is ready.
     * - Sets up UI options.
     * - Moves camera either to initial saved address or triggers permission flow.
     */
    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        map.uiSettings.isZoomControlsEnabled = true

        // If we already have a location from address, move there immediately.
        val existingLat = initialLat
        val existingLng = initialLng
        if (existingLat != null && existingLng != null) {
            val latLng = LatLng(existingLat, existingLng)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f))
        } else {
            // Otherwise ask for location permission and center to user location.
            checkLocationPermissionAndEnable()
        }
    }

    /**
     * Checks if location permission is granted.
     * - If yes -> enableMyLocation().
     * - If no  -> request permissions using Activity Result API.
     */
    private fun checkLocationPermissionAndEnable() {
        val fine = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fine || coarse) {
            enableMyLocation()
        } else {
            requestLocationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    /**
     * Enables the "my location" layer and moves the camera:
     * - to last known device location when available, or
     * - to a default city (Cairo) if no location is available.
     */
    private fun enableMyLocation() {
        if (!::map.isInitialized) return

        val fine = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fine && !coarse) return

        // Show blue "my location" dot on the map.
        map.isMyLocationEnabled = true

        // Try to get the last known location from the fused provider.
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                val latLng = LatLng(loc.latitude, loc.longitude)
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f))
            } else {
                // No last location -> fallback to default region.
                moveCameraToDefault()
            }
        }.addOnFailureListener {
            moveCameraToDefault()
        }
    }

    /**
     * Fallback camera position used when:
     * - Permission is denied, or
     * - No last known location is available.
     *
     * Currently centered on Cairo.
     */
    private fun moveCameraToDefault() {
        // Fallback: Cairo center (can change to your area)
        val cairo = LatLng(30.0444, 31.2357)
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(cairo, 12f))
    }
}
