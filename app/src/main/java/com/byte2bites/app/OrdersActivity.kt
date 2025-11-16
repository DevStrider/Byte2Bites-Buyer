package com.byte2bites.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.byte2bites.app.databinding.ActivityOrdersBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class OrdersActivity : AppCompatActivity() {

    private lateinit var b: ActivityOrdersBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }

    private lateinit var adapter: OrdersAdapter
    private val orders = mutableListOf<Order>()

    // Handler for real-time UI updates (status / time labels)
    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (::adapter.isInitialized && orders.isNotEmpty()) {
                adapter.notifyDataSetChanged()
            }
            // Refresh every 10 seconds (you can change to 5000L if you want faster)
            uiHandler.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityOrdersBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = OrdersAdapter(mutableListOf())
        b.rvOrders.layoutManager = LinearLayoutManager(this)
        b.rvOrders.adapter = adapter

        setupBottomNav()
        setupVoipButton()
        loadOrders()
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(refreshRunnable)
    }

    private fun setupBottomNav() {
        b.navHome.setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java))
        }
        b.navOrders.setOnClickListener {
            // already here
        }
        b.navProfile.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
    }

    private fun setupVoipButton() {
        b.ivVoip.setOnClickListener {
            // Open the VoIP signaling screen
            startActivity(Intent(this, VoipCallActivity::class.java))
        }
    }

    private fun loadOrders() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show()
            return
        }

        val ordersRef = db.reference.child("Buyers").child(uid).child("orders")
        ordersRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<Order>()
                for (child in snapshot.children) {
                    val order = child.getValue(Order::class.java) ?: continue
                    list.add(order)
                }

                // Newest first
                list.sortByDescending { it.timestamp }

                orders.clear()
                orders.addAll(list)
                adapter.submit(orders)

                b.tvEmpty.visibility =
                    if (orders.isEmpty()) View.VISIBLE else View.GONE
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@OrdersActivity, error.message, Toast.LENGTH_SHORT).show()
            }
        })
    }
}
