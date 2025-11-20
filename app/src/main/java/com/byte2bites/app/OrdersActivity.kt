package com.byte2bites.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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
    private lateinit var gestureDetector: GestureDetectorCompat

    private lateinit var adapter: OrdersAdapter
    private val orders = mutableListOf<Order>()

    private val NOTIF_CHANNEL_ID = "orders_channel"
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001

    // For status-change notifications
    private val lastStatusMap = mutableMapOf<String, String>()
    private val sellerNameCache = mutableMapOf<String, String>()

    // Listeners for real-time status updates from sellers
    private val orderStatusListeners = mutableMapOf<String, ValueEventListener>()

    // Swipe sensitivity
    private val SWIPE_THRESHOLD = 100
    private val SWIPE_VELOCITY_THRESHOLD = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityOrdersBinding.inflate(layoutInflater)
        setContentView(b.root)

        createNotificationChannel()
        requestNotificationPermission()

        // Initialize gesture detector
        gestureDetector = GestureDetectorCompat(this, SwipeGestureListener())

        adapter = OrdersAdapter(mutableListOf())
        b.rvOrders.layoutManager = LinearLayoutManager(this)
        b.rvOrders.adapter = adapter

        setupBottomNav()
        setupVoipButton()
        loadOrders()
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

    // Inner class to handle swipe gestures for OrdersActivity
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

                if (Math.abs(diffX) > Math.abs(diffY) &&
                    Math.abs(diffX) > SWIPE_THRESHOLD &&
                    Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {

                    if (diffX > 0) {
                        // Swipe right - go to Home
                        onSwipeRight()
                    } else {
                        // Swipe left - go to Profile
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
        // Navigate to Profile page
        startActivity(Intent(this, ProfileActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    private fun onSwipeRight() {
        // Navigate to Home page
        startActivity(Intent(this, HomeActivity::class.java))
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up all order status listeners
        orderStatusListeners.values.forEach { listener ->
            // Remove listeners if needed
        }
        orderStatusListeners.clear()
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
                val seenIds = mutableSetOf<String>()

                for (child in snapshot.children) {
                    val order = child.getValue(Order::class.java) ?: continue

                    // Set up listener to sync status from seller to buyer
                    setupOrderStatusSyncListener(order)

                    // Check for status changes and notify
                    checkAndNotifyStatusChange(order)

                    list.add(order)
                    seenIds += order.orderId
                }

                // Clean up status map entries and listeners for orders that no longer exist
                val iterator = lastStatusMap.keys.iterator()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    if (!seenIds.contains(key)) {
                        iterator.remove()
                        // Also remove the listener for this order
                        orderStatusListeners.remove(key)?.let { listener ->
                            // Listener is on seller node, so we don't remove from buyer node
                        }
                    }
                }

                // Newest orders at top
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

    // Listen to seller status changes and sync to buyer node
    private fun setupOrderStatusSyncListener(order: Order) {
        val orderId = order.orderId
        val buyerUid = auth.currentUser?.uid ?: return

        // Skip if already listening to this order
        if (orderStatusListeners.containsKey(orderId)) return

        // Get seller UID from first cart item
        val sellerUid = order.items.firstOrNull()?.sellerUid ?: ""
        if (sellerUid.isEmpty()) {
            return
        }

        // Listen to status from seller's node: Sellers/{sellerUid}/orders/{orderId}/status
        val sellerOrderRef = db.reference.child("Sellers").child(sellerUid).child("orders").child(orderId).child("status")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sellerStatus = snapshot.getValue(String::class.java) ?: "DENIED"

                // Update buyer node with seller's status
                updateBuyerOrderStatus(buyerUid, orderId, sellerStatus)
            }

            override fun onCancelled(error: DatabaseError) {
                // If we can't read from seller, set status to DENIED
                updateBuyerOrderStatus(buyerUid, orderId, "DENIED")
            }
        }

        sellerOrderRef.addValueEventListener(listener)
        orderStatusListeners[orderId] = listener
    }

    // Update the status in buyer's node to match seller's node
    private fun updateBuyerOrderStatus(buyerUid: String, orderId: String, sellerStatus: String) {
        val buyerOrderRef = db.reference.child("Buyers").child(buyerUid).child("orders").child(orderId).child("status")

        // Get current status first to compare
        buyerOrderRef.get().addOnSuccessListener { currentSnap ->
            val currentStatus = currentSnap.getValue(String::class.java) ?: "UNKNOWN"

            buyerOrderRef.setValue(sellerStatus).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    // Status updated in buyer node successfully
                    // Check if status changed and notify
                    if (currentStatus != sellerStatus) {
                        // Find the order in our local list to show notification
                        val order = orders.find { it.orderId == orderId }
                        if (order != null) {
                            val updatedOrder = order.copy(status = sellerStatus)
                            showStatusNotification(updatedOrder, getStatusText(sellerStatus))
                            // NEW: Show in-app pop-up (Toast)
                            showStatusPopup(updatedOrder, getStatusText(sellerStatus))
                        }
                    }
                }
            }
        }
    }

    // Check for status changes and show notifications
    private fun checkAndNotifyStatusChange(order: Order) {
        val orderId = order.orderId
        val currentStatus = order.status ?: "UNKNOWN"

        // Check if status changed
        val previousStatus = lastStatusMap[orderId]

        if (previousStatus != null && previousStatus != currentStatus) {
            // Status changed - show notification
            showStatusNotification(order, getStatusText(currentStatus))
            // NEW: Show in-app pop-up (Toast)
            showStatusPopup(order, getStatusText(currentStatus))
        }

        // Update the last known status
        lastStatusMap[orderId] = currentStatus
    }

    private fun getStatusText(status: String): String {
        return when (status) {
            "WAITING_APPROVAL" -> "is waiting for approval"
            "PREPARING" -> "is being prepared"
            "READY" -> "is ready"
            "COMPLETED" -> "has been completed"
            "DENIED" -> "has been denied"
            else -> "status updated: $status"
        }
    }

    // NEW: Show in-app pop-up (Toast) for status changes
    private fun showStatusPopup(order: Order, statusText: String) {
        // Try to get seller UID from first cart item
        val sellerUid = order.items.firstOrNull()?.sellerUid ?: ""

        if (sellerUid.isEmpty()) {
            // Fallback if missing seller UID
            Toast.makeText(
                this,
                "Order status updated: $statusText",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Check cache first
        val cached = sellerNameCache[sellerUid]
        if (cached != null) {
            Toast.makeText(
                this,
                "Order from $cached: $statusText",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Fetch restaurant name from DB for the pop-up
        db.reference.child("Sellers").child(sellerUid).child("name")
            .get()
            .addOnSuccessListener { snap ->
                val restaurantName = snap.getValue(String::class.java) ?: "your restaurant"
                sellerNameCache[sellerUid] = restaurantName
                Toast.makeText(
                    this,
                    "Order from $restaurantName: $statusText",
                    Toast.LENGTH_LONG
                ).show()
            }
            .addOnFailureListener {
                Toast.makeText(
                    this,
                    "Order status updated: $statusText",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    // ==== NOTIFICATION HELPERS ====

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

        // Try to get seller UID from first cart item
        val sellerUid = order.items.firstOrNull()?.sellerUid ?: ""

        if (sellerUid.isEmpty()) {
            // Fallback if missing
            sendStatusNotification("your restaurant", statusText, order.orderId)
            return
        }

        // Check cache first
        val cached = sellerNameCache[sellerUid]
        if (cached != null) {
            sendStatusNotification(cached, statusText, order.orderId)
            return
        }

        // Fetch restaurant name from DB
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

        // When user taps notification → open OrdersActivity
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
            .setPriority(NotificationCompat.PRIORITY_HIGH) // HIGH priority for heads-up
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 100, 200, 300)) // Vibration for heads-up
            .setDefaults(NotificationCompat.DEFAULT_ALL) // Sound, lights, vibration

        try {
            with(NotificationManagerCompat.from(context)) {
                notify(notificationId, builder.build())
            }
        } catch (e: SecurityException) {}
    }
}