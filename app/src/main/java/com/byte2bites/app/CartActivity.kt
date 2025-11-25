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
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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

    private var userCredit: Long = 0
    private var useCreditForPayment: Boolean = false

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

        b.rgDeliveryType.setOnCheckedChangeListener { _, _ ->
            updateTotals()
        }

        b.rgPaymentMethod.setOnCheckedChangeListener { _, checkedId ->
            useCreditForPayment = checkedId == b.rbCredit.id
            updateTotals()
        }

        b.btnCheckout.setOnClickListener { checkout() }

        loadCart()
        loadUserCredit()
    }

    private fun loadUserCredit() {
        val uid = auth.currentUser?.uid ?: return
        db.reference.child("Buyers").child(uid).child("credit")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    userCredit = snapshot.getValue(Long::class.java) ?: 0
                    updateCreditDisplay()
                }

                override fun onCancelled(error: DatabaseError) {
                }
            })
    }

    private fun updateCreditDisplay() {
        b.tvAvailableCredit.text = "Available Credit: ${formatCurrency(userCredit)}"

        b.rbCredit.isEnabled = userCredit > 0
        if (userCredit == 0L && useCreditForPayment) {
            b.rbCash.isChecked = true
            useCreditForPayment = false
        }
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

        if (deliveryType == "PICKUP") {
            val grandTotal = itemsTotal
            b.tvSubtotal.text = formatCurrency(itemsTotal)
            b.tvDeliveryFee.text = "Pickup"

            if (useCreditForPayment) {
                val creditUsed = minOf(userCredit, grandTotal)
                val remaining = grandTotal - creditUsed
                b.tvGrandTotal.text =
                    "Credit: -${formatCurrency(creditUsed)}\nCash: ${formatCurrency(remaining)}"
            } else {
                b.tvGrandTotal.text = formatCurrency(grandTotal)
            }
            return
        }

        val buyerRef = db.reference.child("Buyers").child(uid)

        buyerRef.child("address").get()
            .addOnSuccessListener { addrSnap ->
                val address = addrSnap.getValue(Address::class.java)
                val buyerLat = address?.latitude
                val buyerLng = address?.longitude

                if (address == null || buyerLat == null || buyerLng == null) {
                    b.tvSubtotal.text = formatCurrency(itemsTotal)
                    b.tvDeliveryFee.text = "Add address"
                    b.tvGrandTotal.text = formatCurrency(itemsTotal)
                    return@addOnSuccessListener
                }

                buyerRef.child("cartMeta").child("sellerUid").get()
                    .addOnSuccessListener { metaSnap ->
                        val sellerUid = metaSnap.getValue(String::class.java)
                        if (sellerUid.isNullOrEmpty()) {
                            b.tvSubtotal.text = formatCurrency(itemsTotal)
                            b.tvDeliveryFee.text = "--"
                            b.tvGrandTotal.text = formatCurrency(itemsTotal)
                            return@addOnSuccessListener
                        }

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

                                val distKm = haversineKm(buyerLat, buyerLng, sellerLat, sellerLng)
                                val deliveryFeeCents = calculateDeliveryFeeCents(distKm)

                                b.tvSubtotal.text = formatCurrency(itemsTotal)

                                if (deliveryFeeCents < 0L) {
                                    b.tvDeliveryFee.text = "Not available"
                                    b.tvGrandTotal.text = formatCurrency(itemsTotal)
                                } else {
                                    val grandTotal = itemsTotal + deliveryFeeCents
                                    b.tvDeliveryFee.text = formatCurrency(deliveryFeeCents)

                                    if (useCreditForPayment) {
                                        val creditUsed = minOf(userCredit, grandTotal)
                                        val remaining = grandTotal - creditUsed
                                        b.tvGrandTotal.text =
                                            "Credit: -${formatCurrency(creditUsed)}\nCash: ${
                                                formatCurrency(remaining)
                                            }"
                                    } else {
                                        b.tvGrandTotal.text = formatCurrency(grandTotal)
                                    }
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
        val cartItemRef =
            db.reference.child("Buyers").child(uid).child("cart").child(item.productID)

        if (q <= 0) {
            cartItemRef.removeValue()
            return
        }

        if (q <= item.quantity) {
            cartItemRef.child("quantity").setValue(q)
            return
        }

        val sellerUid = item.sellerUid
        if (sellerUid.isBlank()) {
            cartItemRef.child("quantity").setValue(q)
            return
        }

        val productQtyRef = db.reference
            .child("Sellers")
            .child(sellerUid)
            .child("products")
            .child(item.productID)
            .child("quantity")

        productQtyRef.get()
            .addOnSuccessListener { snap ->
                try {
                    val stock = snap.value?.toString()?.toIntOrNull() ?: 0

                    if (stock <= 0) {
                        Toast.makeText(
                            this@CartActivity,
                            "This item is currently out of stock.",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@addOnSuccessListener
                    }

                    if (q > stock) {
                        Toast.makeText(
                            this@CartActivity,
                            "Only $stock available from this restaurant.",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@addOnSuccessListener
                    }

                    cartItemRef.child("quantity").setValue(q)
                } catch (e: Exception) {
                    Toast.makeText(
                        this@CartActivity,
                        "Error checking stock, please try again.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(
                    this@CartActivity,
                    "Couldn't check stock, please try again.",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    // ===== CHECKOUT + ORDER CREATION =====

    private fun checkout() {
        val uid = auth.currentUser?.uid ?: run {
            Toast.makeText(this, "You are not logged in", Toast.LENGTH_SHORT).show()
            return
        }
        if (items.isEmpty()) {
            Toast.makeText(this, "Cart is empty", Toast.LENGTH_SHORT).show()
            return
        }

        val deliveryType = getSelectedDeliveryType()

        if (useCreditForPayment) {
            val itemsTotal = totalCents(items)
            val deliveryFee = calculateFinalDeliveryFee(deliveryType, uid, itemsTotal)
            if (deliveryFee < 0) {
                Toast.makeText(this, "Cannot calculate delivery fee", Toast.LENGTH_SHORT).show()
                return
            }
            val grandTotal = itemsTotal + deliveryFee

            if (userCredit < grandTotal) {
                Toast.makeText(this, "Not enough credit available", Toast.LENGTH_LONG).show()
                return
            }
        }

        val buyerRef = db.reference.child("Buyers").child(uid)
        val orderItems = items.toList()
        val sellerUids = orderItems.map { it.sellerUid }.distinct().filter { it.isNotEmpty() }
        if (sellerUids.size != 1) {
            Toast.makeText(this, "Cart must contain items from one restaurant", Toast.LENGTH_LONG)
                .show()
            return
        }
        val sellerUidForCart = sellerUids.first()

        if (deliveryType == "DELIVERY") {
            buyerRef.child("address").get().addOnSuccessListener { snap ->
                val addr = snap.getValue(Address::class.java)
                val buyerLat = addr?.latitude
                val buyerLng = addr?.longitude

                if (addr == null) {
                    Toast.makeText(this, "Please add your address first", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, AddressActivity::class.java))
                } else if (buyerLat == null || buyerLng == null) {
                    Toast.makeText(
                        this,
                        "Please pick your location on the map for delivery",
                        Toast.LENGTH_LONG
                    ).show()
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
                Toast.makeText(this, "Failed to load address: ${e.message}", Toast.LENGTH_LONG)
                    .show()
            }
        } else {
            buyerRef.child("address").get().addOnSuccessListener { snap ->
                val addr = snap.getValue(Address::class.java)
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

    private fun calculateFinalDeliveryFee(deliveryType: String, uid: String, itemsTotal: Long): Long {
        if (deliveryType == "PICKUP") return 0L
        return try {
            DELIVERY_FEE_0_TO_10_KM_CENTS
        } catch (e: Exception) {
            -1L
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

        val deliveryFeeCents =
            if (deliveryType == "PICKUP") 0L else calculateFinalDeliveryFee(
                deliveryType,
                uid,
                itemsTotalCents
            )
        val orderTotal = itemsTotalCents + deliveryFeeCents
        val creditUsed = if (useCreditForPayment) minOf(userCredit, orderTotal) else 0L
        val cashAmount = orderTotal - creditUsed

        if (deliveryType == "PICKUP") {
            val updates = hashMapOf<String, Any?>()

            val buyerOrderMap = hashMapOf<String, Any?>(
                "orderId" to orderId,
                "buyerUid" to uid,
                "totalCents" to orderTotal,
                "address" to address,
                "timestamp" to ts,
                "items" to orderItems,
                "deliveryFeeCents" to deliveryFeeCents,
                "deliveryType" to deliveryType,
                "creditUsed" to creditUsed,
                "cashAmount" to cashAmount
            )

            updates["Buyers/$uid/orders/$orderId"] = buyerOrderMap

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
                    updates["$sellerBase/creditUsed"] = creditUsed
                    updates["$sellerBase/cashAmount"] = cashAmount
                }
            }

            if (creditUsed > 0) {
                updates["Buyers/$uid/credit"] = userCredit - creditUsed
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
            val buyerLat = address?.latitude
            val buyerLng = address?.longitude

            if (buyerLat == null || buyerLng == null) {
                Toast.makeText(this, "Address location missing for delivery", Toast.LENGTH_LONG)
                    .show()
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
                        Toast.makeText(
                            this,
                            "Seller location not configured for delivery",
                            Toast.LENGTH_LONG
                        ).show()
                        return@addOnSuccessListener
                    }

                    val distKm = haversineKm(buyerLat, buyerLng, sellerLat, sellerLng)
                    val calculatedDeliveryFeeCents = calculateDeliveryFeeCents(distKm)

                    if (calculatedDeliveryFeeCents < 0L) {
                        Toast.makeText(
                            this,
                            "Delivery not available for distance > 30 km. Please choose pickup.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@addOnSuccessListener
                    }

                    val finalOrderTotal = itemsTotalCents + calculatedDeliveryFeeCents
                    val finalCreditUsed =
                        if (useCreditForPayment) minOf(userCredit, finalOrderTotal) else 0L
                    val finalCashAmount = finalOrderTotal - finalCreditUsed

                    val buyerOrderMap = hashMapOf<String, Any?>(
                        "orderId" to orderId,
                        "buyerUid" to uid,
                        "totalCents" to finalOrderTotal,
                        "address" to address,
                        "timestamp" to ts,
                        "items" to orderItems,
                        "deliveryFeeCents" to calculatedDeliveryFeeCents,
                        "deliveryType" to deliveryType,
                        "creditUsed" to finalCreditUsed,
                        "cashAmount" to finalCashAmount
                    )

                    val updates = hashMapOf<String, Any?>()
                    updates["Buyers/$uid/orders/$orderId"] = buyerOrderMap

                    orderItems.groupBy { it.sellerUid }.forEach { (sellerUid, sellerItems) ->
                        if (!sellerUid.isNullOrEmpty()) {
                            val sellerBase = "Sellers/$sellerUid/orders/$orderId"
                            val itemsTotalForSeller = totalCents(sellerItems)
                            val totalForSeller =
                                itemsTotalForSeller + if (sellerUid == sellerUidForCart) calculatedDeliveryFeeCents else 0L
                            updates["$sellerBase/orderId"] = orderId
                            updates["$sellerBase/buyerUid"] = uid
                            updates["$sellerBase/timestamp"] = ts
                            updates["$sellerBase/totalCents"] = totalForSeller
                            updates["$sellerBase/items"] = sellerItems
                            updates["$sellerBase/deliveryFeeCents"] =
                                if (sellerUid == sellerUidForCart) calculatedDeliveryFeeCents else 0L
                            updates["$sellerBase/deliveryType"] = deliveryType
                            updates["$sellerBase/status"] = "WAITING_APPROVAL"
                            updates["$sellerBase/creditUsed"] = finalCreditUsed
                            updates["$sellerBase/cashAmount"] = finalCashAmount
                        }
                    }

                    if (finalCreditUsed > 0) {
                        updates["Buyers/$uid/credit"] = userCredit - finalCreditUsed
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
                    Toast.makeText(
                        this,
                        "Failed to load seller location: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    // ===== helpers =====

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

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0
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
            else -> -1L
        }
    }

    // ==== NOTIFICATIONS (heads-up) ====

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Orders"
            val desc = "Order status and confirmations"
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

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "orders")  // 👈 VERY IMPORTANT
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
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 100, 200, 300))
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        try {
            with(NotificationManagerCompat.from(this)) {
                notify(orderId.hashCode(), builder.build())
            }
        } catch (e: SecurityException) {
        }
    }
}
