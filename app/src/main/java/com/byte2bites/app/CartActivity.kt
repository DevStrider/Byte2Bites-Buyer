package com.byte2bites.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCartBinding.inflate(layoutInflater)
        setContentView(b.root)

        createNotificationChannel()
        requestNotificationPermission()

        // Back arrow
        b.ivBack.setOnClickListener { finish() }

        adapter = CartAdapter(mutableListOf(), ::inc, ::dec)
        b.rvCart.layoutManager = LinearLayoutManager(this)
        b.rvCart.adapter = adapter

        // Recalculate totals when user switches pickup / delivery
        b.rgDeliveryType.setOnCheckedChangeListener { _, _ ->
            updateTotals()
        }

        b.btnCheckout.setOnClickListener { checkout() }

        loadCart()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            // Permission result handled, notifications will work if granted
        }
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
                    adapter.submit(list)
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

        val selectedDeliveryType = getSelectedDeliveryType()

        // PICKUP -> absolutely NO delivery fee
        if (selectedDeliveryType == "PICKUP") {
            b.tvTotal.text = "Total (pickup): ${formatCurrency(itemsTotal)}"
            return
        }

        // DELIVERY -> use exactly deliveryInfo from seller
        db.reference.child("Buyers").child(uid).child("cartMeta").child("sellerUid")
            .get()
            .addOnSuccessListener { metaSnap ->
                val sellerUid = metaSnap.getValue(String::class.java)
                if (sellerUid.isNullOrEmpty()) {
                    b.tvTotal.text = "Total: ${formatCurrency(itemsTotal)}"
                    return@addOnSuccessListener
                }

                db.reference.child("Sellers").child(sellerUid).child("deliveryInfo")
                    .get()
                    .addOnSuccessListener { feeSnap ->
                        val feeStr = feeSnap.getValue(String::class.java) ?: "0"
                        // EXACT fee from DB (e.g. "60" -> 60.00), no bonus added
                        val deliveryFeeCents = parsePrice(feeStr)
                        val grandTotal = itemsTotal + deliveryFeeCents
                        b.tvTotal.text =
                            "Items: ${formatCurrency(itemsTotal)}  + Delivery: ${formatCurrency(deliveryFeeCents)}  = Total: ${formatCurrency(grandTotal)}"
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

        // DELIVERY: must have address
        if (deliveryType == "DELIVERY") {
            buyerRef.child("address").get().addOnSuccessListener { snap ->
                val addr = snap.getValue(Address::class.java)
                if (addr == null) {
                    Toast.makeText(this, "Please add your address first", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, AddressActivity::class.java))
                } else {
                    placeOrder(
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
            // PICKUP: address optional
            buyerRef.child("address").get().addOnSuccessListener { snap ->
                val addr = snap.getValue(Address::class.java) // can be null
                placeOrder(
                    uid = uid,
                    buyerRef = buyerRef,
                    orderItems = orderItems,
                    sellerUidForCart = sellerUidForCart,
                    deliveryType = deliveryType,
                    address = addr
                )
            }.addOnFailureListener {
                placeOrder(
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
     * Place order without stock validation or inventory updates
     */
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
                    // REMOVED: updateProductQuantities(orderItems)
                    buyerRef.child("cart").removeValue()
                    buyerRef.child("cartMeta").removeValue()
                    Toast.makeText(this, "Order placed (pickup)!", Toast.LENGTH_LONG).show()
                    showOrderPlacedNotification(orderId, sellerUidForCart)
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
                    // EXACT fee from DB
                    val deliveryFeeCents = parsePrice(deliveryStr)
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
                            // REMOVED: updateProductQuantities(orderItems)
                            buyerRef.child("cart").removeValue()
                            buyerRef.child("cartMeta").removeValue()
                            Toast.makeText(this, "Order placed!", Toast.LENGTH_LONG).show()
                            showOrderPlacedNotification(orderId, sellerUidForCart)
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

    // REMOVED: updateProductQuantities method completely

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

    // ==== NOTIFICATIONS ====

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Orders"
            val desc = "Order status and confirmations"
            // HIGH importance for heads-up notifications
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIF_CHANNEL_ID, name, importance).apply {
                description = desc
                // Enable features for heads-up notifications
                enableLights(true)
                lightColor = android.graphics.Color.GREEN
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 100, 200, 300)
                setShowBadge(true)
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
            true
        }
    }

    private fun showOrderPlacedNotification(orderId: String, sellerUid: String) {
        if (!hasNotificationPermission()) return

        // Fetch restaurant name from Sellers/{uid}/name
        db.reference.child("Sellers").child(sellerUid).child("name")
            .get()
            .addOnSuccessListener { snap ->
                val restaurantName = snap.getValue(String::class.java) ?: "your restaurant"
                sendOrderPlacedNotification(orderId, restaurantName)
            }
            .addOnFailureListener {
                sendOrderPlacedNotification(orderId, "your restaurant")
            }
    }

    private fun sendOrderPlacedNotification(orderId: String, restaurantName: String) {
        if (!hasNotificationPermission()) return

        // When user taps → open Orders screen
        val intent = Intent(this, OrdersActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingFlags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else
                PendingIntent.FLAG_UPDATE_CURRENT

        val pendingIntent = PendingIntent.getActivity(
            this,
            orderId.hashCode(),
            intent,
            pendingFlags
        )

        val title = getString(R.string.app_name)   // e.g. "Nastique"
        val text = "Your order from $restaurantName has been placed."

        val builder = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_orders)
            .setContentTitle(title)          // App name
            .setContentText(text)            // Includes restaurant name
            .setPriority(NotificationCompat.PRIORITY_HIGH) // HIGH priority for heads-up
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 100, 200, 300)) // Vibration for heads-up
            .setDefaults(NotificationCompat.DEFAULT_ALL) // Sound, lights, vibration

        try {
            with(NotificationManagerCompat.from(this)) {
                notify(orderId.hashCode(), builder.build())
            }
        } catch (e: SecurityException) { }
    }
}