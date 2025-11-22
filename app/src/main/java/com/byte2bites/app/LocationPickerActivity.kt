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

class LocationPickerActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityLocationPickerBinding
    private lateinit var map: GoogleMap

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private var initialLat: Double? = null
    private var initialLng: Double? = null

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

        // If AddressActivity passes an initial location (existing address)
        initialLat = intent.getDoubleExtra("initial_lat", Double.NaN)
            .takeIf { !it.isNaN() }
        initialLng = intent.getDoubleExtra("initial_lng", Double.NaN)
            .takeIf { !it.isNaN() }

        val mapFragment =
            supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        binding.ivBack.setOnClickListener { finish() }

        binding.btnConfirmLocation.setOnClickListener {
            if (!::map.isInitialized) return@setOnClickListener
            val center: LatLng = map.cameraPosition.target
            val data = intent.apply {
                putExtra("lat", center.latitude)
                putExtra("lng", center.longitude)
            }
            setResult(Activity.RESULT_OK, data)
            finish()
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        map.uiSettings.isZoomControlsEnabled = true

        // If we already have a location from address, move there
        val existingLat = initialLat
        val existingLng = initialLng
        if (existingLat != null && existingLng != null) {
            val latLng = LatLng(existingLat, existingLng)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f))
        } else {
            checkLocationPermissionAndEnable()
        }
    }

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

        map.isMyLocationEnabled = true

        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                val latLng = LatLng(loc.latitude, loc.longitude)
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f))
            } else {
                moveCameraToDefault()
            }
        }.addOnFailureListener {
            moveCameraToDefault()
        }
    }

    private fun moveCameraToDefault() {
        // Fallback: Cairo center (can change to your area)
        val cairo = LatLng(30.0444, 31.2357)
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(cairo, 12f))
    }
}
