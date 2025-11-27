package com.byte2bites.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.byte2bites.app.databinding.ActivityAddressBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

/**
 * Screen where the buyer can view and edit their saved address.
 *
 * Responsibilities:
 * - Load existing address from /Buyers/{uid}/address.
 * - Let user edit building/street/apartment/floor.
 * - Open LocationPickerActivity to choose lat/lng on a map.
 * - Save the complete Address object back to Firebase.
 */
class AddressActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddressBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    // Coordinates are kept in memory, NOT shown as numbers
    private var selectedLatitude: Double? = null
    private var selectedLongitude: Double? = null

    /**
     * Launcher that opens the map picker and receives the chosen coordinates.
     * When the user confirms in LocationPickerActivity, we store the lat/lng
     * and update the UI status text.
     */
    private val locationPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val lat = data?.getDoubleExtra("lat", Double.NaN)
                val lng = data?.getDoubleExtra("lng", Double.NaN)
                if (lat != null && !lat.isNaN() && lng != null && !lng.isNaN()) {
                    selectedLatitude = lat
                    selectedLongitude = lng
                    binding.tvLocationStatus.text = "Location selected (tap to change)"
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddressBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        // Back arrow closes this screen.
        binding.ivBack.setOnClickListener { finish() }

        // Tap card to open map picker.
        binding.cardChooseLocation.setOnClickListener {
            openLocationPicker()
        }

        // Load existing address if present.
        loadAddress()

        // Save button -> validates fields and writes to Firebase.
        binding.btnSaveAddress.setOnClickListener {
            saveAddress()
        }
    }

    /**
     * Opens the map picker activity, passing the current coordinates if any.
     */
    private fun openLocationPicker() {
        val intent = Intent(this, LocationPickerActivity::class.java)
        selectedLatitude?.let { intent.putExtra("initial_lat", it) }
        selectedLongitude?.let { intent.putExtra("initial_lng", it) }
        locationPickerLauncher.launch(intent)
    }

    /**
     * Loads the saved address for the currently logged-in user and populates
     * the UI fields. Also restores previously saved coordinates.
     */
    private fun loadAddress() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            Toast.makeText(this, "User not logged in.", Toast.LENGTH_SHORT).show()
            return
        }

        val addressRef = database.reference.child("Buyers").child(uid).child("address")

        addressRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return

                val address = snapshot.getValue(Address::class.java) ?: return

                binding.etBuildingName.setText(address.buildingName ?: "")
                binding.etStreetName.setText(address.streetName ?: "")
                binding.etApartmentNumber.setText(address.apartmentNumber ?: "")
                binding.etFloorNumber.setText(address.floorNumber ?: "")

                selectedLatitude = address.latitude
                selectedLongitude = address.longitude

                binding.tvLocationStatus.text =
                    if (selectedLatitude != null && selectedLongitude != null)
                        "Location selected (tap to change)"
                    else
                        "Tap to choose on map"
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(
                    this@AddressActivity,
                    "Failed to load address: ${error.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }

    /**
     * Validates the address fields and map location, then saves an Address object
     * under /Buyers/{uid}/address in Firebase Realtime Database.
     */
    private fun saveAddress() {
        val buildingName = binding.etBuildingName.text.toString().trim()
        val streetName = binding.etStreetName.text.toString().trim()
        val apartmentNumber = binding.etApartmentNumber.text.toString().trim()
        val floorNumber = binding.etFloorNumber.text.toString().trim()

        // Textual address parts must all be filled.
        if (buildingName.isEmpty() ||
            streetName.isEmpty() ||
            apartmentNumber.isEmpty() ||
            floorNumber.isEmpty()
        ) {
            Toast.makeText(this, "Please fill all address fields", Toast.LENGTH_SHORT).show()
            return
        }

        // Ensure user actually picked location on the map.
        if (selectedLatitude == null || selectedLongitude == null) {
            Toast.makeText(
                this,
                "Please choose your location on the map.",
                Toast.LENGTH_LONG
            ).show()
            openLocationPicker()
            return
        }

        val uid = auth.currentUser?.uid
        if (uid == null) {
            Toast.makeText(this, "Cannot save address. User not logged in.", Toast.LENGTH_SHORT)
                .show()
            return
        }

        val address = Address(
            buildingName = buildingName,
            streetName = streetName,
            apartmentNumber = apartmentNumber,
            floorNumber = floorNumber,
            latitude = selectedLatitude,
            longitude = selectedLongitude
        )

        database.reference.child("Buyers").child(uid).child("address")
            .setValue(address)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Toast.makeText(this, "Address saved successfully!", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(
                        this,
                        "Failed to save address: ${task.exception?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }
}
