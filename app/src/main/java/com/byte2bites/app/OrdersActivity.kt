package com.byte2bites.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
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

    private val NOTIF_CHANNEL_ID = "orders_channel"

    // For status-change notifications (virtual status based on time)
    private val lastStatusMap = mutableMapOf<String, String>()
    private val sellerNameCache = mutableMapOf<String, String>()

    // Handler for periodic status/time updates + notifications
    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (::adapter.isInitialized && orders.isNotEmpty()) {
                for (order in orders) {
                    val newStatus = computeStatusForOrder(order)
                    val oldStatus = lastStatusMap[order.orderId]

                    if (oldStatus != null && newStatus != oldStatus) {
                        showStatusNotification(order, newStatus)
                    }
                    lastStatusMap[order.orderId] = newStatus
                }
                adapter.notifyDataSetChanged()
            }
            uiHandler.postDelayed(this, 1_000L) // every 1s
        }
    }

    // ==== Swipe support ====
    private lateinit var gestureDetector: GestureDetectorCompat
    private val SWIPE_THRESHOLD = 100
    private val SWIPE_VELOCITY_THRESHOLD = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityOrdersBinding.inflate(layoutInflater)
        setContentView(b.root)

        createNotificationChannel()

        // Gesture detector for swipe between screens
        gestureDetector = GestureDetectorCompat(this, SwipeGestureListener())

        // Adapter with callback for "Call restaurant"
        adapter = OrdersAdapter(mutableListOf()) { order ->
            callRestaurant(order)
        }
        b.rvOrders.layoutManager = LinearLayoutManager(this)
        b.rvOrders.adapter = adapter

        setupBottomNav()
        setupVoipButton()
        loadOrders()

        uiHandler.post(refreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(refreshRunnable)
    }

    // === Swipe handling ===

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return if (gestureDetector.onTouchEvent(event)) {
            true
        } else {
            super.onTouchEvent(event)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let { gestureDetector.onTouchEvent(it) }
        return super.dispatchTouchEvent(ev)
    }

    private inner class SwipeGestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean = true

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (e1 == null) return false

            return try {
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                if (kotlin.math.abs(diffX) > kotlin.math.abs(diffY) &&
                    kotlin.math.abs(diffX) > SWIPE_THRESHOLD &&
                    kotlin.math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD
                ) {
                    if (diffX > 0) {
                        onSwipeRight()
                    } else {
                        onSwipeLeft()
                    }
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    private fun onSwipeLeft() {
        startActivity(Intent(this, ProfileActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    private fun onSwipeRight() {
        startActivity(Intent(this, HomeActivity::class.java))
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    // ===== Bottom nav & VoIP button =====

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
        // Global VoIP button in toolbar/icon (manual call screen)
        b.ivVoip.setOnClickListener {
            startActivity(Intent(this, VoipCallActivity::class.java))
        }
    }

    // ===== Load orders & virtual status map =====

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
                val seenIds = mutableSetOf<String>()

                for (child in snapshot.children) {
                    val order = child.getValue(Order::class.java) ?: continue
                    list.add(order)
                    seenIds += order.orderId

                    if (!lastStatusMap.containsKey(order.orderId)) {
                        lastStatusMap[order.orderId] = computeStatusForOrder(order)
                    }
                }

                // Clean status entries for orders that disappeared
                val it = lastStatusMap.keys.iterator()
                while (it.hasNext()) {
                    val key = it.next()
                    if (!seenIds.contains(key)) it.remove()
                }

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

    // ===== CALL RESTAURANT (per order) =====

    private fun callRestaurant(order: Order) {
        val sellerUid = order.items.firstOrNull()?.sellerUid

        if (sellerUid.isNullOrEmpty()) {
            Toast.makeText(this, "No seller info for this order.", Toast.LENGTH_LONG).show()
            return
        }

        // We only pass the seller UID; IP and port are entered (or remembered) inside VoipCallActivity.
        val intent = Intent(this, VoipCallActivity::class.java).apply {
            putExtra(VoipCallActivity.EXTRA_CALLEE_UID, sellerUid)
        }
        startActivity(intent)
    }

    // ===== STATUS LOGIC (time-based flow) =====

    private fun computeStatusForOrder(order: Order): String {
        val now = System.currentTimeMillis()
        val ageSeconds = ((now - order.timestamp) / 1000).toInt().coerceAtLeast(0)
        val type = order.deliveryType

        return when {
            ageSeconds < 20 -> "Waiting for seller approval"
            ageSeconds < 40 -> "Preparing"
            ageSeconds < 60 ->
                if (type == "PICKUP") "Ready for pickup" else "Delivering"
            else -> "Delivered"
        }
    }

    // ===== NOTIFICATION HELPERS =====

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
            true
        }
    }

    private fun showStatusNotification(order: Order, statusText: String) {
        if (!hasNotificationPermission()) return

        val sellerUid = order.items.firstOrNull()?.sellerUid ?: ""

        if (sellerUid.isEmpty()) {
            sendStatusNotification("your restaurant", statusText, order.orderId)
            return
        }

        val cached = sellerNameCache[sellerUid]
        if (cached != null) {
            sendStatusNotification(cached, statusText, order.orderId)
            return
        }

        db.reference.child("Sellers").child(sellerUid).child("name")
            .get()
            .addOnSuccessListener { snap ->
                val restaurantName = snap.getValue(String::class.java) ?: "your restaurant"
                sellerNameCache[sellerUid] = restaurantName
                sendStatusNotification(restaurantName, statusText, order.orderId)
            }
            .addOnFailureListener {
                sendStatusNotification("your restaurant", statusText, order.orderId)
            }
    }

    private fun sendStatusNotification(
        restaurantName: String,
        statusText: String,
        orderId: String
    ) {
        if (!hasNotificationPermission()) return

        val context = this

        val intent = Intent(context, OrdersActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingFlags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else
                PendingIntent.FLAG_UPDATE_CURRENT

        val pendingIntent = PendingIntent.getActivity(
            context,
            (orderId + statusText).hashCode(),
            intent,
            pendingFlags
        )

        val title = getString(R.string.app_name)
        val text = "Order from $restaurantName: $statusText"

        val notificationKey = "$orderId-$statusText"
        val notificationId = notificationKey.hashCode()

        val builder = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_orders)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            with(NotificationManagerCompat.from(context)) {
                notify(notificationId, builder.build())
            }
        } catch (_: SecurityException) { }
    }
}
