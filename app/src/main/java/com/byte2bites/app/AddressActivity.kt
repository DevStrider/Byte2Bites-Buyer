package com.byte2bites.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.byte2bites.app.databinding.ActivityAddressBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class AddressActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAddressBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddressBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        binding.ivBack.setOnClickListener {
            finish()
        }
        loadAddress()

        binding.btnSaveAddress.setOnClickListener {
            saveAddress()
        }
    }
    private fun loadAddress() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            Toast.makeText(this, "User not logged in.", Toast.LENGTH_SHORT).show()
            return
        }

        val addressRef = database.reference.child("Buyers").child(uid).child("address")

        addressRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val address = snapshot.getValue(Address::class.java)
                    binding.etBuildingName.setText(address?.buildingName)
                    binding.etStreetName.setText(address?.streetName)
                    binding.etApartmentNumber.setText(address?.apartmentNumber)
                    binding.etFloorNumber.setText(address?.floorNumber)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@AddressActivity, "Failed to load address: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }
    private fun saveAddress() {
        val buildingName = binding.etBuildingName.text.toString().trim()
        val streetName = binding.etStreetName.text.toString().trim()
        val apartmentNumber = binding.etApartmentNumber.text.toString().trim()
        val floorNumber = binding.etFloorNumber.text.toString().trim()

        if (buildingName.isEmpty() || streetName.isEmpty() || apartmentNumber.isEmpty() || floorNumber.isEmpty()) {
            Toast.makeText(this, "Please fill all address fields", Toast.LENGTH_SHORT).show()
            return
        }

        val uid = auth.currentUser?.uid
        if (uid == null) {
            Toast.makeText(this, "Cannot save address. User not logged in.", Toast.LENGTH_SHORT).show()
            return
        }

        val address = Address(buildingName, streetName, apartmentNumber, floorNumber)

        // Save the address object under the user's UID
        database.reference.child("Buyers").child(uid).child("address").setValue(address)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Toast.makeText(this, "Address saved successfully!", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, "Failed to save address: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
    }
}
