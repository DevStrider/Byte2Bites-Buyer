package com.byte2bites.app

import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.byte2bites.app.databinding.ActivityHomeBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class HomeActivity : AppCompatActivity() {

    private lateinit var b: ActivityHomeBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseDatabase
    private lateinit var gestureDetector: GestureDetectorCompat

    private lateinit var adapter: SellerAdapter
    private val sellers = mutableListOf<Seller>()

    // Swipe sensitivity - adjust as needed
    private val SWIPE_THRESHOLD = 100
    private val SWIPE_VELOCITY_THRESHOLD = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)

        supportActionBar?.hide()

        auth = FirebaseAuth.getInstance()
        db = FirebaseDatabase.getInstance()

        // Initialize gesture detector
        gestureDetector = GestureDetectorCompat(this, SwipeGestureListener())

        // Restaurants / shops list
        adapter = SellerAdapter(mutableListOf()) { seller ->
            startActivity(Intent(this, SellerProductsActivity::class.java).apply {
                putExtra("sellerUid", seller.uid)
                putExtra("sellerName", seller.name)
            })
        }
        b.rvProducts.layoutManager = LinearLayoutManager(this)
        b.rvProducts.adapter = adapter

        // Cart icon in header
        b.ivCart.setOnClickListener {
            startActivity(Intent(this, CartActivity::class.java))
        }

        setupBottomNav()
        setupSearch()

        loadUserGreeting()
        loadSellers()
    }

    // Handle touch events for swipe gestures
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return if (gestureDetector.onTouchEvent(event)) {
            true
        } else {
            super.onTouchEvent(event)
        }
    }

    // Also handle touch events on the entire layout
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let { gestureDetector.onTouchEvent(it) }
        return super.dispatchTouchEvent(ev)
    }

    // Inner class to handle swipe gestures
    private inner class SwipeGestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (e1 == null) return false

            try {
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                // Check if it's a horizontal swipe (X movement greater than Y movement)
                if (Math.abs(diffX) > Math.abs(diffY) &&
                    Math.abs(diffX) > SWIPE_THRESHOLD &&
                    Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {

                    if (diffX > 0) {
                        // Swipe right - go to Profile
                        onSwipeRight()
                    } else {
                        // Swipe left - go to Orders
                        onSwipeLeft()
                    }
                    return true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            return false
        }
    }

    private fun onSwipeLeft() {
        // Navigate to Orders page
        startActivity(Intent(this, OrdersActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    private fun onSwipeRight() {
        // Navigate to Profile page
        startActivity(Intent(this, ProfileActivity::class.java))
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    // -------- TOP: Welcome NameOfUser --------

    private fun loadUserGreeting() {
        val currentUser = auth.currentUser ?: return

        db.reference.child("Buyers").child(currentUser.uid)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val userProfile = snapshot.getValue(User::class.java)
                    val name = userProfile?.fullName?.takeIf { !it.isNullOrBlank() } ?: "there"
                    b.tvTitleHome.text = "Welcome $name"
                }

                override fun onCancelled(error: DatabaseError) {
                    b.tvTitleHome.text = "Welcome"
                }
            })
    }

    // -------- SEARCH: restaurants/shops only --------

    private fun setupSearch() {
        b.etSearch.addTextChangedListener { text ->
            filterSellers(text?.toString().orEmpty())
        }
    }

    private fun filterSellers(query: String) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            adapter.submit(sellers)
        } else {
            val filtered = sellers.filter { seller ->
                seller.name?.lowercase()?.contains(q) == true
            }
            adapter.submit(filtered)
        }
    }

    // -------- LOAD SELLERS --------

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

                    val q = b.etSearch.text?.toString().orEmpty()
                    filterSellers(q)

                    if (sellers.isEmpty()) {
                        Toast.makeText(
                            this@HomeActivity,
                            "No restaurants found.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
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

    // -------- BOTTOM NAV --------

    private fun setupBottomNav() {
        b.navHome.setOnClickListener {
            // no-op (already on Home)
        }
        b.navOrders.setOnClickListener {
            startActivity(Intent(this, OrdersActivity::class.java))
        }
        b.navProfile.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
    }
}