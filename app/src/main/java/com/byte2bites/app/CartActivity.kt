package com.byte2bites.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
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
import java.net.InetAddress
import kotlin.math.*

class CartActivity : AppCompatActivity() {

    private lateinit var b: ActivityCartBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }

    private lateinit var adapter: CartAdapter
    private val items = mutableListOf<CartItem>()

    private val NOTIF_CHANNEL_ID = "orders_channel"
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001

    // 💰 Delivery prices (in cents)
    private val DELIVERY_FEE_0_TO_10_KM_CENTS = 1500L  // 15.00
    private val DELIVERY_FEE_10_TO_20_KM_CENTS = 2500L // 25.00
    private val DELIVERY_FEE_20_TO_30_KM_CENTS = 3500L // 35.00

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

    // ===== NOTIFICATION PERMISSION =====

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
        // nothing special needed here
    }

    // ===== CART LOADING + TOTALS =====

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

    /**
     * Compute items total + dynamic delivery fee (based on distance) and
     * show them in the 3 rows:
     *   - Subtotal
     *   - Delivery
     *   - Total
     */
    private fun updateTotals() {
        val uid = auth.currentUser?.uid ?: return
        val itemsTotal = totalCents(items)

        if (items.isEmpty()) {
            b.tvSubtotal.text = formatCurrency(0)
            b.tvDeliveryFee.text = "--"
            b.tvGrandTotal.text = formatCurrency(0)
            return
        }

        val deliveryType = getSelectedDeliveryType()

        // PICKUP → no delivery fee
        if (deliveryType == "PICKUP") {
            b.tvSubtotal.text = formatCurrency(itemsTotal)
            b.tvDeliveryFee.text = "Pickup"
            b.tvGrandTotal.text = formatCurrency(itemsTotal)
            return
        }

        // DELIVERY → need buyer address and seller location
        val buyerRef = db.reference.child("Buyers").child(uid)

        // 1) Buyer address (with lat/long)
        buyerRef.child("address").get()
            .addOnSuccessListener { addrSnap ->
                val address = addrSnap.getValue(Address::class.java)
                val buyerLat = address?.latitude
                val buyerLng = address?.longitude

                if (address == null || buyerLat == null || buyerLng == null) {
                    // Has items but no map location yet
                    b.tvSubtotal.text = formatCurrency(itemsTotal)
                    b.tvDeliveryFee.text = "Add address"
                    b.tvGrandTotal.text = formatCurrency(itemsTotal)
                    return@addOnSuccessListener
                }

                // 2) Which seller is this cart for?
                buyerRef.child("cartMeta").child("sellerUid").get()
                    .addOnSuccessListener { metaSnap ->
                        val sellerUid = metaSnap.getValue(String::class.java)
                        if (sellerUid.isNullOrEmpty()) {
                            b.tvSubtotal.text = formatCurrency(itemsTotal)
                            b.tvDeliveryFee.text = "--"
                            b.tvGrandTotal.text = formatCurrency(itemsTotal)
                            return@addOnSuccessListener
                        }

                        // 3) Seller location
                        db.reference.child("Sellers").child(sellerUid).get()
                            .addOnSuccessListener { sellerSnap ->
                                val sLatAny = sellerSnap.child("latitude").value
                                val sLngAny = sellerSnap.child("longitude").value

                                val sellerLat = sLatAny?.toString()?.toDoubleOrNull()
                                val sellerLng = sLngAny?.toString()?.toDoubleOrNull()

                                if (sellerLat == null || sellerLng == null) {
                                    b.tvSubtotal.text = formatCurrency(itemsTotal)
                                    b.tvDeliveryFee.text = "Seller location missing"
                                    b.tvGrandTotal.text = formatCurrency(itemsTotal)
                                    return@addOnSuccessListener
                                }

                                // 4) Distance + fee
                                val distKm = haversineKm(buyerLat, buyerLng, sellerLat, sellerLng)
                                val deliveryFeeCents = calculateDeliveryFeeCents(distKm)

                                b.tvSubtotal.text = formatCurrency(itemsTotal)

                                if (deliveryFeeCents < 0L) {
                                    // > 30km: no delivery
                                    b.tvDeliveryFee.text = "Not available"
                                    b.tvGrandTotal.text = formatCurrency(itemsTotal)
                                } else {
                                    val grandTotal = itemsTotal + deliveryFeeCents
                                    b.tvDeliveryFee.text = formatCurrency(deliveryFeeCents)
                                    b.tvGrandTotal.text = formatCurrency(grandTotal)
                                }
                            }
                            .addOnFailureListener {
                                b.tvSubtotal.text = formatCurrency(itemsTotal)
                                b.tvDeliveryFee.text = "--"
                                b.tvGrandTotal.text = formatCurrency(itemsTotal)
                            }
                    }
            }
            .addOnFailureListener {
                b.tvSubtotal.text = formatCurrency(itemsTotal)
                b.tvDeliveryFee.text = "--"
                b.tvGrandTotal.text = formatCurrency(itemsTotal)
            }
    }

    private fun inc(item: CartItem) = setQty(item, item.quantity + 1)
    private fun dec(item: CartItem) = setQty(item, (item.quantity - 1).coerceAtLeast(0))

    private fun setQty(item: CartItem, q: Int) {
        val uid = auth.currentUser?.uid ?: return
        val ref = db.reference.child("Buyers").child(uid).child("cart").child(item.productID)
        if (q == 0) ref.removeValue() else ref.child("quantity").setValue(q)
    }

    // ===== CHECKOUT + ORDER CREATION =====

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

        if (deliveryType == "DELIVERY") {
            // DELIVERY: must have full address + map location
            buyerRef.child("address").get().addOnSuccessListener { snap ->
                val addr = snap.getValue(Address::class.java)
                val buyerLat = addr?.latitude
                val buyerLng = addr?.longitude

                if (addr == null) {
                    Toast.makeText(this, "Please add your address first", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, AddressActivity::class.java))
                } else if (buyerLat == null || buyerLng == null) {
                    Toast.makeText(this, "Please pick your location on the map for delivery", Toast.LENGTH_LONG).show()
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
            // PICKUP: address optional
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
     * Check quantities against latest inventory in DB.
     * If OK -> creates order.
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

        val buyerIp = getLocalIpAddress()
        val buyerPort = 5000 // your fixed VoIP port

        if (deliveryType == "PICKUP") {
            // PICKUP: no delivery fee
            val deliveryFeeCents = 0L
            val orderTotal = itemsTotalCents + deliveryFeeCents

            // 🔸 Buyer order: NO status field
            val buyerOrderMap = hashMapOf<String, Any?>(
                "orderId" to orderId,
                "buyerUid" to uid,
                "totalCents" to orderTotal,
                "address" to address,
                "timestamp" to ts,
                "items" to orderItems,
                "deliveryFeeCents" to deliveryFeeCents,
                "deliveryType" to deliveryType,
                "buyerIp" to buyerIp,
                "buyerPort" to buyerPort
                // intentionally NO "status"
            )

            val updates = hashMapOf<String, Any?>()
            updates["Buyers/$uid/orders/$orderId"] = buyerOrderMap

            // 🔹 Seller orders WITH status
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
                    updates["$sellerBase/buyerIp"] = buyerIp
                    updates["$sellerBase/buyerPort"] = buyerPort
                }
            }

            rootRef.updateChildren(updates).addOnCompleteListener { task ->
                if (task.isSuccessful) {
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
            // DELIVERY: dynamic fee based on distance
            val buyerLat = address?.latitude
            val buyerLng = address?.longitude

            if (buyerLat == null || buyerLng == null) {
                Toast.makeText(this, "Address location missing for delivery", Toast.LENGTH_LONG).show()
                return
            }

            rootRef.child("Sellers").child(sellerUidForCart)
                .get()
                .addOnSuccessListener { sellerSnap ->
                    val sLatAny = sellerSnap.child("latitude").value
                    val sLngAny = sellerSnap.child("longitude").value

                    val sellerLat = sLatAny?.toString()?.toDoubleOrNull()
                    val sellerLng = sLngAny?.toString()?.toDoubleOrNull()

                    if (sellerLat == null || sellerLng == null) {
                        Toast.makeText(this, "Seller location not configured for delivery", Toast.LENGTH_LONG).show()
                        return@addOnSuccessListener
                    }

                    val distKm = haversineKm(buyerLat, buyerLng, sellerLat, sellerLng)
                    val deliveryFeeCents = calculateDeliveryFeeCents(distKm)

                    if (deliveryFeeCents < 0L) {
                        Toast.makeText(
                            this,
                            "Delivery not available for distance > 30 km. Please choose pickup.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@addOnSuccessListener
                    }

                    val orderTotal = itemsTotalCents + deliveryFeeCents

                    // 🔸 Buyer order: NO status field
                    val buyerOrderMap = hashMapOf<String, Any?>(
                        "orderId" to orderId,
                        "buyerUid" to uid,
                        "totalCents" to orderTotal,
                        "address" to address,
                        "timestamp" to ts,
                        "items" to orderItems,
                        "deliveryFeeCents" to deliveryFeeCents,
                        "deliveryType" to deliveryType,
                        "buyerIp" to buyerIp,
                        "buyerPort" to buyerPort
                        // intentionally NO "status"
                    )

                    val updates = hashMapOf<String, Any?>()
                    updates["Buyers/$uid/orders/$orderId"] = buyerOrderMap

                    // 🔹 Seller orders WITH status
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
                            updates["$sellerBase/buyerIp"] = buyerIp
                            updates["$sellerBase/buyerPort"] = buyerPort
                        }
                    }

                    rootRef.updateChildren(updates).addOnCompleteListener { task ->
                        if (task.isSuccessful) {
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
                    Toast.makeText(this, "Failed to load seller location: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    // === IP helper ===
    private fun getLocalIpAddress(): String? {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wm.connectionInfo.ipAddress
            InetAddress.getByAddress(
                byteArrayOf(
                    (ipInt and 0xff).toByte(),
                    (ipInt shr 8 and 0xff).toByte(),
                    (ipInt shr 16 and 0xff).toByte(),
                    (ipInt shr 24 and 0xff).toByte()
                )
            ).hostAddress
        } catch (e: Exception) {
            null
        }
    }

    // ====== helpers ======

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

    // ==== Distance + delivery fee helpers ====

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0 // Earth radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * asin(sqrt(a))
        return R * c
    }

    private fun calculateDeliveryFeeCents(distanceKm: Double): Long {
        return when {
            distanceKm <= 10.0 -> DELIVERY_FEE_0_TO_10_KM_CENTS
            distanceKm <= 20.0 -> DELIVERY_FEE_10_TO_20_KM_CENTS
            distanceKm <= 30.0 -> DELIVERY_FEE_20_TO_30_KM_CENTS
            else -> -1L // means "no delivery"
        }
    }

    // ==== NOTIFICATIONS (heads-up) ====

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Orders"
            val desc = "Order status and confirmations"
            // HIGH importance for heads-up notifications
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIF_CHANNEL_ID, name, importance).apply {
                description = desc
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

        val title = getString(R.string.app_name)
        val text = "Your order from $restaurantName has been placed."

        val builder = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_orders)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH) // heads-up
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 100, 200, 300))
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        try {
            with(NotificationManagerCompat.from(this)) {
                notify(orderId.hashCode(), builder.build())
            }
        } catch (e: SecurityException) { }
    }
}
