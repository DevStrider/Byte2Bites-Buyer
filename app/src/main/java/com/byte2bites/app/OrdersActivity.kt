package com.byte2bites.app

import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityOrdersBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = OrdersAdapter(mutableListOf())
        b.rvOrders.layoutManager = LinearLayoutManager(this)
        b.rvOrders.adapter = adapter

        b.ivBack.setOnClickListener { finish() }

        loadOrders()
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
                val now = System.currentTimeMillis()
                val freshOrders = mutableListOf<Order>()
                val toDelete = mutableListOf<Order>()

                for (child in snapshot.children) {
                    val order = child.getValue(Order::class.java) ?: continue
                    val ageSeconds = ((now - order.timestamp) / 1000).toInt()

                    // > 90 seconds → delete
                    if (ageSeconds > 90) {
                        toDelete.add(order)
                    } else {
                        freshOrders.add(order)
                    }
                }

                // Delete expired orders from Buyers and Sellers
                deleteExpiredOrders(uid, toDelete)

                // Newest first
                freshOrders.sortByDescending { it.timestamp }

                orders.clear()
                orders.addAll(freshOrders)
                adapter.submit(orders)

                b.tvEmpty.visibility =
                    if (orders.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@OrdersActivity, error.message, Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun deleteExpiredOrders(buyerUid: String, expiredOrders: List<Order>) {
        if (expiredOrders.isEmpty()) return

        val updates = hashMapOf<String, Any?>()

        for (order in expiredOrders) {
            val orderId = order.orderId
            // Remove from buyer
            updates["Buyers/$buyerUid/orders/$orderId"] = null

            // Remove from each seller
            order.items.groupBy { it.sellerUid }.forEach { (sellerUid, _) ->
                if (!sellerUid.isNullOrEmpty()) {
                    updates["Sellers/$sellerUid/orders/$orderId"] = null
                }
            }
        }

        db.reference.updateChildren(updates)
    }
}
