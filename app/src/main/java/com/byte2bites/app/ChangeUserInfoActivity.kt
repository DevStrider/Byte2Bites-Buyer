package com.byte2bites.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.byte2bites.app.databinding.ActivityChangeUserInfoBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Activity that allows the user to update their basic profile info:
 * - Full name
 * - Phone number
 *
 * Data is stored under /Buyers/{uid}.
 */
class ChangeUserInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChangeUserInfoBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChangeUserInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        // Back arrow -> close this screen.
        binding.ivBack.setOnClickListener {
            finish()
        }

        // Load initial values for the form.
        loadCurrentUserInfo()

        // Save button -> update in Firebase.
        binding.btnSaveChanges.setOnClickListener {
            updateUserInfo()
        }
    }

    /**
     * Reads the current user object from /Buyers/{uid} and populates
     * the full name and phone number text fields.
     */
    private fun loadCurrentUserInfo() {
        val uid = auth.currentUser?.uid ?: return
        val userRef = database.reference.child("Buyers").child(uid)

        userRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val user = snapshot.getValue(User::class.java)
                    binding.etFullName.setText(user?.fullName)
                    binding.etPhoneNumber.setText(user?.phoneNumber)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@ChangeUserInfoActivity, "Failed to load info: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    /**
     * Validates fullName and phoneNumber, then updates the corresponding
     * fields on the /Buyers/{uid} node.
     */
    private fun updateUserInfo() {
        val fullName = binding.etFullName.text.toString().trim()
        val phoneNumber = binding.etPhoneNumber.text.toString().trim()

        if (fullName.isEmpty() || phoneNumber.isEmpty()) {
            Toast.makeText(this, "Fields cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        val uid = auth.currentUser?.uid ?: return
        val userRef = database.reference.child("Buyers").child(uid)

        val updates = mapOf<String, Any>(
            "fullName" to fullName,
            "phoneNumber" to phoneNumber
        )

        userRef.updateChildren(updates)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Toast.makeText(this, "Profile updated successfully!", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, "Update failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }
}
