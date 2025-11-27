package com.byte2bites.app

import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.byte2bites.app.databinding.ActivityForgotPasswordBinding
import com.google.firebase.auth.FirebaseAuth

/**
 * Activity that lets the user request a password reset email.
 *
 * Flow:
 * - User enters email.
 * - We validate format.
 * - Call FirebaseAuth.sendPasswordResetEmail(email).
 */
class ForgotPasswordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityForgotPasswordBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityForgotPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Hide action bar for clean UI
        supportActionBar?.hide()

        auth = FirebaseAuth.getInstance()

        // Back arrow -> close the screen.
        binding.ivBack.setOnClickListener {
            finish()
        }

        // Main action: send reset email.
        binding.btnSendResetLink.setOnClickListener {
            sendPasswordResetEmail()
        }
    }

    /**
     * Validates that the email is non-empty & correctly formatted,
     * then sends a password reset email using FirebaseAuth.
     */
    private fun sendPasswordResetEmail() {
        val email = binding.etEmail.text.toString().trim()

        if (email.isEmpty()) {
            binding.tilEmail.error = "Email is required"
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = "Please enter a valid email"
            return
        }

        binding.tilEmail.error = null

        auth.sendPasswordResetEmail(email)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Toast.makeText(
                        this,
                        "Password reset instructions sent to your email.",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                } else {
                    Toast.makeText(
                        this,
                        "Failed to send reset email: ${task.exception?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }
}
