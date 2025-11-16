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
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
                // For each order, recompute status and notify if changed
                for (order in orders) {
                    val newStatus = computeStatusForOrder(order)
                    val oldStatus = lastStatusMap[order.orderId]

                    // Only notify when status actually changes
                    if (oldStatus != null && newStatus != oldStatus) {
                        showStatusNotification(order, newStatus)
                    }
                    lastStatusMap[order.orderId] = newStatus
                }
                // Refresh list so time/status labels update in UI (if user is on this screen)
                adapter.notifyDataSetChanged()
            }
            // Run again every 1 second (accurate timer)
            uiHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityOrdersBinding.inflate(layoutInflater)
        setContentView(b.root)

        createNotificationChannel()

        adapter = OrdersAdapter(mutableListOf())
        b.rvOrders.layoutManager = LinearLayoutManager(this)
        b.rvOrders.adapter = adapter

        setupBottomNav()
        setupVoipButton()
        loadOrders()

        // Start periodic status updates once OrdersActivity is created.
        // This keeps running while the app process is alive, even in background.
        uiHandler.post(refreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop the runnable when this Activity is destroyed
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
                    list.add(order)
                    seenIds += order.orderId

                    // Initialize virtual status for new orders (no notification yet)
                    if (!lastStatusMap.containsKey(order.orderId)) {
                        lastStatusMap[order.orderId] = computeStatusForOrder(order)
                    }
                }

                // Clean up status map entries for orders that no longer exist
                val iterator = lastStatusMap.keys.iterator()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    if (!seenIds.contains(key)) {
                        iterator.remove()
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

    // ==== STATUS LOGIC (time-based flow) ====

    /**
     * Virtual status from timestamp + delivery type.
     * Sequence:
     *  0–20s   -> Waiting for seller approval
     *  20–40s  -> Preparing
     *  40–60s  -> Ready for pickup (pickup) / Delivering (delivery)
     *  >60s    -> Delivered
     */
    private fun computeStatusForOrder(order: Order): String {
        val now = System.currentTimeMillis()
        val ageSeconds = ((now - order.timestamp) / 1000).toInt().coerceAtLeast(0)
        val type = order.deliveryType ?: "DELIVERY"

        return when {
            ageSeconds < 20 -> "Waiting for seller approval"
            ageSeconds < 40 -> "Preparing"
            ageSeconds < 60 ->
                if (type == "PICKUP") "Ready for pickup" else "Delivering"
            else -> "Delivered"
        }
    }

    // ==== NOTIFICATION HELPERS ====

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

    /**
     * Show a notification when the virtual status changes.
     * Title = app name (Nastique).
     * Text  = "Order from <RestaurantName>: <Status>".
     */
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
            (orderId + statusText).hashCode(), // requestCode unique per status
            intent,
            pendingFlags
        )

        val title = getString(R.string.app_name)     // e.g. "Nastique"
        val text = "Order from $restaurantName: $statusText"

        // UNIQUE notification ID per (order + status)
        val notificationKey = "$orderId-$statusText"
        val notificationId = notificationKey.hashCode()

        val builder = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_orders)
            .setContentTitle(title)          // App name
            .setContentText(text)            // Restaurant name + status
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            with(NotificationManagerCompat.from(context)) {
                notify(notificationId, builder.build())
            }
        } catch (e: SecurityException) {}
    }
}
