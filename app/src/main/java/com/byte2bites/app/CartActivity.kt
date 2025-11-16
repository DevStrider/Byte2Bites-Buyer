package com.byte2bites.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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

    private val NOTIF_CHANNEL_ID = "orders_channel"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCartBinding.inflate(layoutInflater)
        setContentView(b.root)

        createNotificationChannel()

        // Back arrow
        b.ivBack.setOnClickListener { finish() }

        adapter = CartAdapter(mutableListOf(), ::inc, ::dec)
        b.rvCart.layoutManager = LinearLayoutManager(this)
        b.rvCart.adapter = adapter

        // Checkout
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
                        if (item != null && item.quantity > 0) list.add(item)
                    }
                    items.clear()
                    items.addAll(list)
                    adapter.submit(items)
                    updateTotals()
                }
                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(this@CartActivity, error.message, Toast.LENGTH_SHORT).show()
                }
            })
    }

    /** compute items total + (optional) delivery fee and show in tvTotal */
    private fun updateTotals() {
        val uid = auth.currentUser?.uid ?: return
        val itemsTotal = totalCents(items)

        // If pickup selected -> no delivery fee
        val selectedDeliveryType = getSelectedDeliveryType()
        if (selectedDeliveryType == "PICKUP") {
            b.tvTotal.text = "Total (pickup): ${formatCurrency(itemsTotal)}"
            return
        }

        db.reference.child("Buyers").child(uid).child("cartMeta").child("sellerUid")
            .get()
            .addOnSuccessListener { metaSnap ->
                val sellerUid = metaSnap.getValue(String::class.java)
                if (sellerUid.isNullOrEmpty()) {
                    b.tvTotal.text = "Total: ${formatCurrency(itemsTotal)}"
                    return@addOnSuccessListener
                }
                // Base fee from seller node (old logic)
                db.reference.child("Sellers").child(sellerUid).child("deliveryInfo")
                    .get()
                    .addOnSuccessListener { feeSnap ->
                        val feeStr = feeSnap.getValue(String::class.java) ?: "0"
                        val baseDeliveryFeeCents = parsePrice(feeStr)

                        // Simple dynamic logic: add a small extra based on #items
                        val dynamicFeeCents = calculateDynamicDeliveryFee(baseDeliveryFeeCents, items)
                        val grandTotal = itemsTotal + dynamicFeeCents
                        b.tvTotal.text =
                            "Items: ${formatCurrency(itemsTotal)}  + Delivery: ${formatCurrency(dynamicFeeCents)}  = Total: ${formatCurrency(grandTotal)}"
                    }
                    .addOnFailureListener {
                        b.tvTotal.text = "Total: ${formatCurrency(itemsTotal)}"
                    }
            }
    }

    private fun inc(item: CartItem) = setQty(item, item.quantity + 1)
    private fun dec(item: CartItem) = setQty(item, (item.quantity - 1).coerceAtLeast(0))

    private fun setQty(item: CartItem, q: Int) {
        val uid = auth.currentUser?.uid ?: return
        val ref = db.reference.child("Buyers").child(uid).child("cart").child(item.productID)
        if (q == 0) ref.removeValue() else ref.child("quantity").setValue(q)
    }

    private fun checkout() {
        val uid = auth.currentUser?.uid ?: run {
            Toast.makeText(this, "You are not logged in", Toast.LENGTH_SHORT).show(); return
        }
        if (items.isEmpty()) {
            Toast.makeText(this, "Cart is empty", Toast.LENGTH_SHORT).show(); return
        }

        val deliveryType = getSelectedDeliveryType() // "DELIVERY" or "PICKUP"

        val buyerRef = db.reference.child("Buyers").child(uid)
        val orderItems = items.toList()
        val sellerUids = orderItems.map { it.sellerUid }.distinct().filter { it.isNotEmpty() }
        if (sellerUids.size != 1) {
            Toast.makeText(this, "Cart must contain items from one restaurant", Toast.LENGTH_LONG).show()
            return
        }
        val sellerUidForCart = sellerUids.first()

        // If delivery chosen, enforce saved address
        if (deliveryType == "DELIVERY") {
            buyerRef.child("address").get().addOnSuccessListener { snap ->
                val addr = snap.getValue(Address::class.java)
                if (addr == null) {
                    Toast.makeText(this, "Please add your address first", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, AddressActivity::class.java))
                } else {
                    verifyStockAndPlaceOrder(
                        uid = uid,
                        buyerRef = buyerRef,
                        orderItems = orderItems,
                        sellerUidForCart = sellerUidForCart,
                        deliveryType = deliveryType,
                        address = addr
                    )
                }
            }.addOnFailureListener { e ->
                Toast.makeText(this, "Failed to load address: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            // PICKUP: no address required, but we can still store last saved one if exists (optional)
            buyerRef.child("address").get().addOnSuccessListener { snap ->
                val addr = snap.getValue(Address::class.java) // can be null
                verifyStockAndPlaceOrder(
                    uid = uid,
                    buyerRef = buyerRef,
                    orderItems = orderItems,
                    sellerUidForCart = sellerUidForCart,
                    deliveryType = deliveryType,
                    address = addr
                )
            }.addOnFailureListener {
                // Even if address load fails, pickup doesn't strictly need it
                verifyStockAndPlaceOrder(
                    uid = uid,
                    buyerRef = buyerRef,
                    orderItems = orderItems,
                    sellerUidForCart = sellerUidForCart,
                    deliveryType = deliveryType,
                    address = null
                )
            }
        }
    }

    /**
     * Check quantities against the latest inventory in the DB.
     * If OK -> creates order, updates inventory, clears cart.
     */
    private fun verifyStockAndPlaceOrder(
        uid: String,
        buyerRef: DatabaseReference,
        orderItems: List<CartItem>,
        sellerUidForCart: String,
        deliveryType: String,
        address: Address?
    ) {
        val rootRef = db.reference

        rootRef.child("Sellers").get().addOnSuccessListener { sellersSnap ->
            val outOfStockItems = mutableListOf<String>()

            for (item in orderItems) {
                val productNode = sellersSnap
                    .child(item.sellerUid)
                    .child("products")
                    .child(item.productID)

                val qtyStr = productNode.child("quantity").getValue(String::class.java) ?: "0"
                val available = qtyStr.toIntOrNull() ?: 0

                if (item.quantity > available) {
                    outOfStockItems += "${item.name} (only $available left)"
                }
            }

            if (outOfStockItems.isNotEmpty()) {
                // Show alert dialog (UI requirement)
                AlertDialog.Builder(this)
                    .setTitle("Not enough stock")
                    .setMessage(
                        "Some items are not available in the requested quantity:\n\n" +
                                outOfStockItems.joinToString("\n") +
                                "\n\nPlease update your cart."
                    )
                    .setPositiveButton("OK", null)
                    .show()
                return@addOnSuccessListener
            }

            // If we reach here -> all quantities OK. Proceed to place order.
            placeOrder(
                uid = uid,
                buyerRef = buyerRef,
                orderItems = orderItems,
                sellerUidForCart = sellerUidForCart,
                deliveryType = deliveryType,
                address = address
            )
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Failed to check stock: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun placeOrder(
        uid: String,
        buyerRef: DatabaseReference,
        orderItems: List<CartItem>,
        sellerUidForCart: String,
        deliveryType: String,
        address: Address?
    ) {
        val itemsTotalCents = totalCents(orderItems)
        val rootRef = db.reference
        val ts = System.currentTimeMillis()
        val orderId = rootRef.push().key ?: ts.toString()

        if (deliveryType == "PICKUP") {
            val deliveryFeeCents = 0L
            val orderTotal = itemsTotalCents + deliveryFeeCents

            val order = Order(
                orderId = orderId,
                buyerUid = uid,
                totalCents = orderTotal,
                address = address,
                timestamp = ts,
                items = orderItems,
                deliveryFeeCents = deliveryFeeCents,
                deliveryType = deliveryType,
                status = "WAITING_APPROVAL"
            )

            val updates = hashMapOf<String, Any?>()
            updates["Buyers/$uid/orders/$orderId"] = order

            orderItems.groupBy { it.sellerUid }.forEach { (sellerUid, sellerItems) ->
                if (!sellerUid.isNullOrEmpty()) {
                    val sellerBase = "Sellers/$sellerUid/orders/$orderId"
                    val itemsTotalForSeller = totalCents(sellerItems)
                    val totalForSeller = itemsTotalForSeller
                    updates["$sellerBase/orderId"] = orderId
                    updates["$sellerBase/buyerUid"] = uid
                    updates["$sellerBase/timestamp"] = ts
                    updates["$sellerBase/totalCents"] = totalForSeller
                    updates["$sellerBase/items"] = sellerItems
                    updates["$sellerBase/deliveryFeeCents"] = 0L
                    updates["$sellerBase/deliveryType"] = deliveryType
                    updates["$sellerBase/status"] = "WAITING_APPROVAL"
                }
            }

            rootRef.updateChildren(updates).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    updateProductQuantities(orderItems)
                    buyerRef.child("cart").removeValue()
                    buyerRef.child("cartMeta").removeValue()
                    Toast.makeText(this, "Order placed (pickup)!", Toast.LENGTH_LONG).show()
                    showOrderPlacedNotification(orderId)
                    finish()
                } else {
                    Toast.makeText(
                        this,
                        "Failed to place order: ${task.exception?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } else {
            // DELIVERY
            rootRef.child("Sellers").child(sellerUidForCart).child("deliveryInfo")
                .get()
                .addOnSuccessListener { feeSnap ->
                    val deliveryStr = feeSnap.getValue(String::class.java) ?: "0"
                    val baseDeliveryFeeCents = parsePrice(deliveryStr)
                    val deliveryFeeCents = calculateDynamicDeliveryFee(baseDeliveryFeeCents, orderItems)
                    val orderTotal = itemsTotalCents + deliveryFeeCents

                    val order = Order(
                        orderId = orderId,
                        buyerUid = uid,
                        totalCents = orderTotal,
                        address = address,
                        timestamp = ts,
                        items = orderItems,
                        deliveryFeeCents = deliveryFeeCents,
                        deliveryType = deliveryType,
                        status = "WAITING_APPROVAL"
                    )

                    val updates = hashMapOf<String, Any?>()
                    updates["Buyers/$uid/orders/$orderId"] = order

                    orderItems.groupBy { it.sellerUid }.forEach { (sellerUid, sellerItems) ->
                        if (!sellerUid.isNullOrEmpty()) {
                            val sellerBase = "Sellers/$sellerUid/orders/$orderId"
                            val itemsTotalForSeller = totalCents(sellerItems)
                            val totalForSeller = itemsTotalForSeller +
                                    if (sellerUid == sellerUidForCart) deliveryFeeCents else 0L
                            updates["$sellerBase/orderId"] = orderId
                            updates["$sellerBase/buyerUid"] = uid
                            updates["$sellerBase/timestamp"] = ts
                            updates["$sellerBase/totalCents"] = totalForSeller
                            updates["$sellerBase/items"] = sellerItems
                            updates["$sellerBase/deliveryFeeCents"] =
                                if (sellerUid == sellerUidForCart) deliveryFeeCents else 0L
                            updates["$sellerBase/deliveryType"] = deliveryType
                            updates["$sellerBase/status"] = "WAITING_APPROVAL"
                        }
                    }

                    rootRef.updateChildren(updates).addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            updateProductQuantities(orderItems)
                            buyerRef.child("cart").removeValue()
                            buyerRef.child("cartMeta").removeValue()
                            Toast.makeText(this, "Order placed!", Toast.LENGTH_LONG).show()
                            showOrderPlacedNotification(orderId)
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
                    Toast.makeText(this, "Failed to load delivery fee: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    // Decrease product quantity (quantity is stored as STRING in DB)
    private fun updateProductQuantities(orderItems: List<CartItem>) {
        for (item in orderItems) {
            val sellerUid = item.sellerUid
            val productId = item.productID
            if (sellerUid.isEmpty() || productId.isEmpty()) continue

            val qtyRef = db.reference.child("Sellers").child(sellerUid)
                .child("products").child(productId).child("quantity")

            qtyRef.get().addOnSuccessListener { snap ->
                val currentStr = snap.getValue(String::class.java) ?: return@addOnSuccessListener
                val current = currentStr.toIntOrNull() ?: return@addOnSuccessListener
                val newQty = (current - item.quantity).coerceAtLeast(0)
                qtyRef.setValue(newQty.toString())
            }
        }
    }

    // helpers
    private fun parsePrice(priceString: String?): Long {
        if (priceString.isNullOrBlank()) return 0L
        val digitsOnly = priceString.filter { it.isDigit() }
        if (digitsOnly.isEmpty()) return 0L
        val units = digitsOnly.toLongOrNull() ?: return 0L
        return units * 100L
    }

    private fun totalCents(items: List<CartItem>): Long =
        items.sumOf { parsePrice(it.price) * it.quantity }

    private fun formatCurrency(cents: Long): String {
        val whole = cents / 100
        val frac = (cents % 100).toString().padStart(2, '0')
        return "$$whole.$frac"
    }

    private fun getSelectedDeliveryType(): String {
        val checkedId = b.rgDeliveryType.checkedRadioButtonId
        val rb = findViewById<RadioButton>(checkedId)
        return if (rb != null && rb.id == b.rbPickup.id) "PICKUP" else "DELIVERY"
    }

    // Simple "dynamic" delivery fee logic:
    // base fee + 0.5 per item
    private fun calculateDynamicDeliveryFee(baseFeeCents: Long, items: List<CartItem>): Long {
        val extraPerItemCents = 50L
        val extra = items.sumOf { it.quantity * extraPerItemCents }
        return baseFeeCents + extra
    }

    // ==== NOTIFICATIONS ====

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Orders"
            val desc = "Order status and confirmations"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(NOTIF_CHANNEL_ID, name, importance).apply {
                description = desc
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // pre-Android 13 doesn't need this permission
        }
    }

    private fun showOrderPlacedNotification(orderId: String) {
        // If permission not granted, just skip the notification (no crash)
        if (!hasNotificationPermission()) {
            return
        }

        val builder = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_orders)
            .setContentTitle("Order placed")
            .setContentText("Your order #${orderId.takeLast(6)} has been placed successfully.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        try {
            with(NotificationManagerCompat.from(this)) {
                notify(orderId.hashCode(), builder.build())
            }
        } catch (e: SecurityException) {
            // In case something weird happens, ignore to avoid crash
        }
    }
}
