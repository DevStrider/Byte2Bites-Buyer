package com.byte2bites.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.byte2bites.app.databinding.ActivityLoginBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        binding.ivBack.setOnClickListener {
            finish()
        }

        binding.btnLogin.setOnClickListener {
            loginUser()
        }

        binding.tvForgotPassword.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }
    }

    private fun loginUser() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please enter email and password", Toast.LENGTH_SHORT).show()
            return
        }

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    // Authentication is successful, now check if user is a Buyer
                    val uid = auth.currentUser?.uid
                    if (uid == null) {
                        Toast.makeText(this, "Login failed: Could not get user details", Toast.LENGTH_LONG).show()
                        return@addOnCompleteListener
                    }

                    database.reference.child("Buyers").child(uid).get()
                        .addOnSuccessListener { dataSnapshot ->
                            if (dataSnapshot.exists()) {
                                // User exists in "Buyers" node -> They are a buyer
                                Toast.makeText(this, "Login Successful!", Toast.LENGTH_SHORT).show()
                                val intent = Intent(this, HomeActivity::class.java)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                startActivity(intent)
                                finish()
                            } else {
                                // User does NOT exist in "Buyers" node -> They are a seller or other user type
                                Toast.makeText(this, "Login Failed: This account is not a buyer account.", Toast.LENGTH_LONG).show()
                                auth.signOut()
                            }
                        }
                        .addOnFailureListener { exception ->
                            // Failed to read database
                            Toast.makeText(this, "Login Failed: ${exception.message}", Toast.LENGTH_LONG).show()
                            auth.signOut()
                        }

                } else {
                    // Authentication itself failed (e.g., wrong password)
                    Toast.makeText(this, "Login Failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
    }
}