package com.byte2bites.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.byte2bites.app.databinding.ActivityLoginBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

/**
 * Activity that handles buyer login using Firebase Authentication.
 *
 * Flow:
 * - User enters email/password.
 * - signInWithEmailAndPassword is called.
 * - After successful auth, we verify that this uid exists under /Buyers.
 * - If it's a buyer account, user is redirected to MainActivity.
 * - Otherwise, login is rejected and user is signed out.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Fullscreen feel for auth
        supportActionBar?.hide()

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        // Back arrow -> close login and return to previous screen.
        binding.ivBack.setOnClickListener {
            finish()
        }

        // Attempt login when the button is clicked.
        binding.btnLogin.setOnClickListener {
            loginUser()
        }

        // Forgot password -> open password reset screen.
        binding.tvForgotPassword.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }
    }

    /**
     * Validates input and attempts to sign the user in with FirebaseAuth.
     * After successful authentication, verifies that the account is a Buyer.
     */
    private fun loginUser() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please enter email and password", Toast.LENGTH_SHORT).show()
            return
        }

        // Firebase Authentication sign in call.
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val uid = auth.currentUser?.uid
                    if (uid == null) {
                        Toast.makeText(
                            this,
                            "Login failed: Could not get user details",
                            Toast.LENGTH_LONG
                        ).show()
                        return@addOnCompleteListener
                    }

                    // Ensure this user is a Buyer (role-based access).
                    database.reference.child("Buyers").child(uid).get()
                        .addOnSuccessListener { dataSnapshot ->
                            if (dataSnapshot.exists()) {
                                Toast.makeText(this, "Login successful!", Toast.LENGTH_SHORT)
                                    .show()

                                // ✅ Go to MainActivity (which hosts Home/Orders/Profile fragments)
                                val intent = Intent(this, MainActivity::class.java)
                                intent.flags =
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                startActivity(intent)
                                finish()
                            } else {
                                // Auth succeeded but not found under /Buyers => wrong role.
                                Toast.makeText(
                                    this,
                                    "Login failed: This account is not a buyer account.",
                                    Toast.LENGTH_LONG
                                ).show()
                                auth.signOut()
                            }
                        }
                        .addOnFailureListener { exception ->
                            Toast.makeText(
                                this,
                                "Login failed: ${exception.message}",
                                Toast.LENGTH_LONG
                            ).show()
                            auth.signOut()
                        }
                } else {
                    Toast.makeText(
                        this,
                        "Login failed: ${task.exception?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }
}
