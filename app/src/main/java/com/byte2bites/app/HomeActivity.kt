package com.byte2bites.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.byte2bites.app.databinding.ActivityHomeBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class HomeActivity : AppCompatActivity() {

    private lateinit var b: ActivityHomeBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseDatabase

    private lateinit var adapter: SellerAdapter
    private val sellers = mutableListOf<Seller>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            b = ActivityHomeBinding.inflate(layoutInflater)
            setContentView(b.root)
        } catch (e: Exception) {
            Log.e("HomeActivity", "Error inflating binding", e)
            Toast.makeText(this, "Layout error in activity_home.xml", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        auth = FirebaseAuth.getInstance()
        db = FirebaseDatabase.getInstance()

        adapter = SellerAdapter(mutableListOf()) { seller ->
            val intent = Intent(this, SellerProductsActivity::class.java)
            intent.putExtra("sellerUid", seller.uid)
            intent.putExtra("sellerName", seller.name)
            startActivity(intent)
        }

        b.rvProducts.layoutManager = LinearLayoutManager(this)
        b.rvProducts.adapter = adapter

        b.ivProfile.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        b.fabCart.setOnClickListener {
            startActivity(Intent(this, CartActivity::class.java))
        }

        loadSellers()
    }

    private fun loadSellers() {
        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show()
            return
        }

        db.reference.child("Sellers")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(root: DataSnapshot) {
                    try {
                        val list = ArrayList<Seller>()
                        for (sellerSnap in root.children) {
                            val uid = sellerSnap.key ?: continue
                            val name = sellerSnap.child("name").getValue(String::class.java)
                            val email = sellerSnap.child("email").getValue(String::class.java)
                            val phone = sellerSnap.child("phone").getValue(String::class.java)
                            list.add(Seller(uid = uid, name = name, email = email, phone = phone))
                        }
                        sellers.clear()
                        sellers.addAll(list)
                        adapter.submit(sellers)

                        if (sellers.isEmpty()) {
                            Toast.makeText(
                                this@HomeActivity,
                                "No restaurants found yet.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (e: Exception) {
                        Log.e("HomeActivity", "Error parsing sellers", e)
                        Toast.makeText(
                            this@HomeActivity,
                            "Error loading restaurants",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("HomeActivity", "DB cancelled: ${error.message}")
                    Toast.makeText(
                        this@HomeActivity,
                        "Failed to load restaurants: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
    }
}
