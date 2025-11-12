package com.byte2bites.app

import android.content.Intent
import android.os.Bundle
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
        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)

        auth = FirebaseAuth.getInstance()
        db = FirebaseDatabase.getInstance()

        // RecyclerView shows restaurants/shops (sellers)
        adapter = SellerAdapter(mutableListOf()) { seller ->
            startActivity(Intent(this, SellerProductsActivity::class.java).apply {
                putExtra("sellerUid", seller.uid)
                putExtra("sellerName", seller.name)
            })
        }
        b.rvProducts.layoutManager = LinearLayoutManager(this)
        b.rvProducts.adapter = adapter

        // Profile icon
        b.ivProfile.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        // Cart FAB
        b.fabCart.setOnClickListener {
            startActivity(Intent(this, CartActivity::class.java))
        }

        // My Orders button
        b.btnOrders.setOnClickListener {
            startActivity(Intent(this, OrdersActivity::class.java))
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
                    val list = ArrayList<Seller>()
                    for (sellerSnap in root.children) {
                        val uid = sellerSnap.key ?: continue
                        val name = sellerSnap.child("name").getValue(String::class.java)
                        val email = sellerSnap.child("email").getValue(String::class.java)
                        val phone = sellerSnap.child("phone").getValue(String::class.java)
                        val profileImageUrl =
                            sellerSnap.child("profileImageUrl").getValue(String::class.java)

                        list.add(
                            Seller(
                                uid = uid,
                                name = name,
                                email = email,
                                phone = phone,
                                profileImageUrl = profileImageUrl
                            )
                        )
                    }
                    sellers.clear()
                    sellers.addAll(list)
                    adapter.submit(sellers)
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(
                        this@HomeActivity,
                        "Failed to load restaurants: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
    }
}
