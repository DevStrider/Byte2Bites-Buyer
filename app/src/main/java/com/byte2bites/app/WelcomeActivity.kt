package com.byte2bites.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.byte2bites.app.databinding.ActivityWelcomeBinding
import com.google.firebase.auth.FirebaseAuth

/**
 * First screen shown when the app launches (entry point for unauthenticated users).
 *
 * Responsibilities:
 * - If a user is already logged in (FirebaseAuth currentUser != null), skip this screen
 *   and redirect directly to MainActivity.
 * - Otherwise, display two main actions:
 *   - Login
 *   - Register
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Full-screen welcome, no action bar.
        supportActionBar?.hide()

        auth = FirebaseAuth.getInstance()

        // If already logged in, go straight to MainActivity (Home/Orders/Profile host).
        val currentUser = auth.currentUser
        if (currentUser != null) {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
            return
        }

        // Otherwise, show login/register buttons.
        setupClickListeners()
    }

    /**
     * Wires up the login and register buttons to their respective activities.
     */
    private fun setupClickListeners() {
        // LOGIN FIRST (primary).
        binding.btnLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        // SIGN UP / CREATE ACCOUNT SECOND.
        binding.btnRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }
}
