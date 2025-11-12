package com.byte2bites.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.byte2bites.app.databinding.ActivityCartBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class CartActivity : AppCompatActivity() {

    private lateinit var b: ActivityCartBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }

    private lateinit var adapter: CartAdapter
    private val items = mutableListOf<CartItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCartBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = CartAdapter(mutableListOf(), ::inc, ::dec)
        b.rvCart.layoutManager = LinearLayoutManager(this)
        b.rvCart.adapter = adapter

        b.btnCheckout.setOnClickListener { checkout() }

        loadCart()
    }

    private fun loadCart() {
        val uid = auth.currentUser?.uid ?: return
        db.reference.child("Buyers").child(uid).child("cart")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val list = ArrayList<CartItem>()
                    for (c in snapshot.children) {
                        val item = c.getValue(CartItem::class.java)
                        if (item != null && item.quantity > 0) {
                            list.add(item)
                        }
                    }
                    items.clear()
                    items.addAll(list)
                    adapter.submit(items)
                    b.tvTotal.text = "Total: ${formatCurrency(totalCents(items))}"
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(this@CartActivity, error.message, Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun inc(item: CartItem) = setQty(item, item.quantity + 1)

    private fun dec(item: CartItem) = setQty(item, (item.quantity - 1).coerceAtLeast(0))

    private fun setQty(item: CartItem, q: Int) {
        val uid = auth.currentUser?.uid ?: return
        val ref = db.reference
            .child("Buyers")
            .child(uid)
            .child("cart")
            .child(item.productID)

        if (q == 0) {
            ref.removeValue()
        } else {
            ref.child("quantity").setValue(q)
        }
    }

    private fun checkout() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            Toast.makeText(this, "You are not logged in", Toast.LENGTH_SHORT).show()
            return
        }

        if (items.isEmpty()) {
            Toast.makeText(this, "Cart is empty", Toast.LENGTH_SHORT).show()
            return
        }

        val buyerRef = db.reference.child("Buyers").child(uid)

        // 1) Get address
        buyerRef.child("address").get()
            .addOnSuccessListener { snap ->
                val addr = snap.getValue(Address::class.java)
                if (addr == null) {
                    Toast.makeText(
                        this,
                        "Please add your address first",
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(Intent(this, AddressActivity::class.java))
                    return@addOnSuccessListener
                }

                // 2) Build order
                val orderId = db.reference.push().key ?: System.currentTimeMillis().toString()
                val orderItems = items.toList()
                val orderTotal = totalCents(orderItems)
                val orderTimestamp = System.currentTimeMillis()

                val order = Order(
                    orderId = orderId,
                    buyerUid = uid,
                    totalCents = orderTotal,
                    address = addr,
                    timestamp = orderTimestamp,
                    items = orderItems
                )

                // 3) Prepare multi-path updates
                val updates = hashMapOf<String, Any?>()

                // Buyer’s orders
                updates["Buyers/$uid/orders/$orderId"] = order

                // Each seller’s view of the order
                orderItems.groupBy { it.sellerUid }.forEach { (sellerUid, sellerItems) ->
                    if (!sellerUid.isNullOrEmpty()) {
                        val sellerBase = "Sellers/$sellerUid/orders/$orderId"
                        updates["$sellerBase/orderId"] = orderId
                        updates["$sellerBase/buyerUid"] = uid
                        updates["$sellerBase/timestamp"] = orderTimestamp
                        updates["$sellerBase/totalCents"] = totalCents(sellerItems)
                        updates["$sellerBase/items"] = sellerItems
                    }
                }

                // 4) Commit updates + clear cart, update quantities
                db.reference.updateChildren(updates)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            updateProductQuantities(orderItems)

                            buyerRef.child("cart").removeValue()
                            buyerRef.child("cartMeta").removeValue()
                            Toast.makeText(this, "Order placed!", Toast.LENGTH_LONG).show()
                            finish()
                        } else {
                            Toast.makeText(
                                this,
                                "Failed to place order: ${task.exception?.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to load address: ${e.message}", Toast.LENGTH_LONG)
                    .show()
            }
    }

    // Decrease quantity for each ordered product
    private fun updateProductQuantities(orderItems: List<CartItem>) {
        for (item in orderItems) {
            val sellerUid = item.sellerUid
            val productId = item.productID
            if (sellerUid.isEmpty() || productId.isEmpty()) continue

            val qtyRef = db.reference
                .child("Sellers")
                .child(sellerUid)
                .child("products")
                .child(productId)
                .child("quantity")

            qtyRef.runTransaction(object : Transaction.Handler {
                override fun doTransaction(currentData: MutableData): Transaction.Result {
                    val current = currentData.getValue(Int::class.java) ?: return Transaction.success(currentData)
                    val newQty = (current - item.quantity).coerceAtLeast(0)
                    currentData.value = newQty
                    return Transaction.success(currentData)
                }

                override fun onComplete(
                    error: DatabaseError?,
                    committed: Boolean,
                    currentData: DataSnapshot?
                ) {
                    // you can log errors if needed
                }
            })
        }
    }

    // helpers

    // price stored like "150" or "$150" -> treat as whole units, convert to cents
    private fun parsePrice(priceString: String?): Long {
        if (priceString.isNullOrBlank()) return 0L
        val digitsOnly = priceString.filter { it.isDigit() }
        if (digitsOnly.isEmpty()) return 0L
        val units = digitsOnly.toLongOrNull() ?: return 0L
        return units * 100L
    }

    private fun totalCents(items: List<CartItem>): Long {
        return items.sumOf { item ->
            parsePrice(item.price) * item.quantity
        }
    }

    private fun formatCurrency(cents: Long): String {
        val whole = cents / 100
        val frac = (cents % 100).toString().padStart(2, '0')
        return "$$whole.$frac"
    }
}
