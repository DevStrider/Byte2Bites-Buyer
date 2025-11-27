package com.byte2bites.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.byte2bites.app.databinding.ActivityRegisterBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

/**
 * Activity that handles buyer registration using Firebase Authentication.
 * After a successful sign-up:
 * - A user node is created under /Buyers/{uid} in Firebase Realtime Database.
 * - User starts at 0 points and 0 credit (wallet).
 * - Then the user is redirected directly to MainActivity.
 */
class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Fullscreen-ish auth screen (hide the default action bar).
        supportActionBar?.hide()

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        // Back icon returns to previous screen (usually Welcome or Login).
        binding.ivBack.setOnClickListener {
            finish()
        }

        // Trigger registration using the entered data.
        binding.btnRegister.setOnClickListener {
            registerUser()
        }
    }

    /**
     * Validates input and creates a new Firebase Auth user.
     * Also creates a User object in Realtime Database under /Buyers/{uid}.
     */
    private fun registerUser() {
        val fullName = binding.etFullName.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val phoneNumber = binding.etPhoneNumber.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        // Basic non-empty validation.
        if (fullName.isEmpty() || email.isEmpty() || phoneNumber.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return
        }

        // Firebase minimum password security rule.
        if (password.length < 6) {
            Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show()
            return
        }

        // Create user in Firebase Authentication using email/password.
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val firebaseUser = auth.currentUser
                    val uid = firebaseUser?.uid

                    if (uid != null) {
                        // Initialize user with 0 points and 0 credit (cents).
                        val user = User(
                            fullName = fullName,
                            email = email,
                            phoneNumber = phoneNumber,
                            points = 0,
                            credit = 0
                        )

                        // Save the profile under /Buyers/{uid}.
                        database.reference.child("Buyers").child(uid).setValue(user)
                            .addOnCompleteListener { dbTask ->
                                if (dbTask.isSuccessful) {
                                    Toast.makeText(
                                        this,
                                        "Registration successful!",
                                        Toast.LENGTH_SHORT
                                    ).show()

                                    // After successful registration, go straight to main buyer flow.
                                    val intent = Intent(this, MainActivity::class.java)
                                    intent.flags =
                                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    startActivity(intent)
                                    finish()
                                } else {
                                    Toast.makeText(
                                        this,
                                        "Database error: ${dbTask.exception?.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                    }
                } else {
                    // Authentication failed (existing user, invalid email, network, etc.).
                    Toast.makeText(
                        this,
                        "Authentication failed: ${task.exception?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }
}
