package com.byte2bites.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.byte2bites.app.databinding.ActivityOrdersBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class OrdersFragment : Fragment() {

    private var _b: ActivityOrdersBinding? = null
    private val b get() = _b!!

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }

    private lateinit var adapter: OrdersAdapter
    private val orders = mutableListOf<Order>()

    private val NOTIF_CHANNEL_ID = "orders_channel"
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001

    // Cache seller names so we don't hit DB every time
    private val sellerNameCache = mutableMapOf<String, String>()

    // For syncing seller status -> buyer node
    private val orderStatusListeners = mutableMapOf<String, ValueEventListener>()
    private val orderStatusRefs = mutableMapOf<String, DatabaseReference>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = ActivityOrdersBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        createNotificationChannel()
        requestNotificationPermission()

        // RecyclerView + adapter with call button
        adapter = OrdersAdapter(mutableListOf()) { order ->
            callRestaurant(order)
        }
        b.rvOrders.layoutManager = LinearLayoutManager(requireContext())
        b.rvOrders.adapter = adapter

        setupVoipButton()
        loadOrders()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // Clean up Firebase listeners for seller order statuses
        orderStatusRefs.forEach { (orderId, ref) ->
            val listener = orderStatusListeners[orderId]
            if (listener != null) {
                ref.removeEventListener(listener)
            }
        }
        orderStatusListeners.clear()
        orderStatusRefs.clear()

        _b = null
    }

    // === Call restaurant from an order ===

    private fun callRestaurant(order: Order) {
        val sellerUid = order.items.firstOrNull()?.sellerUid

        if (sellerUid.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "No seller info for this order.", Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(requireContext(), VoipCallActivity::class.java).apply {
            putExtra(VoipCallActivity.EXTRA_CALLEE_UID, sellerUid)
        }
        startActivity(intent)
    }

    private fun setupVoipButton() {
        b.ivVoip.setOnClickListener {
            startActivity(Intent(requireContext(), VoipCallActivity::class.java))
        }
    }

    // ===== Load orders (buyer node) =====

    private fun loadOrders() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            Toast.makeText(requireContext(), "Not logged in", Toast.LENGTH_SHORT).show()
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

                    // Set up sync from seller -> buyer status for this order
                    setupOrderStatusSyncListener(uid, order)
                }

                // Remove listeners for orders that no longer exist
                val iterator = orderStatusRefs.keys.iterator()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    if (!seenIds.contains(key)) {
                        val ref = orderStatusRefs[key]
                        val listener = orderStatusListeners[key]
                        if (ref != null && listener != null) {
                            ref.removeEventListener(listener)
                        }
                        iterator.remove()
                        orderStatusListeners.remove(key)
                    }
                }

                // Newest orders first
                list.sortByDescending { it.timestamp }

                orders.clear()
                orders.addAll(list)
                adapter.submit(orders)

                b.tvEmpty.visibility =
                    if (orders.isEmpty()) View.VISIBLE else View.GONE
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(requireContext(), error.message, Toast.LENGTH_SHORT).show()
            }
        })
    }

    /**
     * Listen to seller status changes and sync to buyer node:
     *   Sellers/{sellerUid}/orders/{orderId}/status
     * → Buyers/{buyerUid}/orders/{orderId}/status
     */
    private fun setupOrderStatusSyncListener(buyerUid: String, order: Order) {
        val orderId = order.orderId

        // Already listening for this order
        if (orderStatusListeners.containsKey(orderId)) return

        val sellerUid = order.items.firstOrNull()?.sellerUid ?: return
        if (sellerUid.isEmpty()) return

        val sellerOrderRef = db.reference
            .child("Sellers")
            .child(sellerUid)
            .child("orders")
            .child(orderId)
            .child("status")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sellerStatus = snapshot.getValue(String::class.java) ?: return
                updateBuyerOrderStatus(buyerUid, orderId, sellerStatus)
            }

            override fun onCancelled(error: DatabaseError) {
                // ignore
            }
        }

        sellerOrderRef.addValueEventListener(listener)
        orderStatusListeners[orderId] = listener
        orderStatusRefs[orderId] = sellerOrderRef
    }

    /**
     * Update the status in buyer's node to match seller's node, BUT:
     * - If buyer has no status AND sellerStatus == "WAITING_APPROVAL"
     *   → DO NOT write a status for the buyer (keep it "empty" initially).
     * - For later changes (ACCEPTED, PREPARING, etc.) → sync & notify.
     */
    private fun updateBuyerOrderStatus(buyerUid: String, orderId: String, sellerStatus: String) {
        val buyerStatusRef = db.reference
            .child("Buyers")
            .child(buyerUid)
            .child("orders")
            .child(orderId)
            .child("status")

        buyerStatusRef.get().addOnSuccessListener { currentSnap ->
            val currentStatus = currentSnap.getValue(String::class.java) ?: ""

            if (currentStatus.isEmpty() && sellerStatus == "WAITING_APPROVAL") {
                return@addOnSuccessListener
            }

            if (currentStatus == sellerStatus) {
                return@addOnSuccessListener
            }

            buyerStatusRef.setValue(sellerStatus).addOnCompleteListener { task ->
                if (!task.isSuccessful) return@addOnCompleteListener

                val existing = orders.find { it.orderId == orderId }
                val updatedOrder = existing?.copy(status = sellerStatus)

                val statusText = getStatusText(sellerStatus, existing?.deliveryType)

                if (updatedOrder != null) {
                    showStatusNotification(updatedOrder, statusText)
                    showStatusPopup(updatedOrder, statusText)
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Order status updated: $statusText",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun getStatusText(status: String, deliveryType: String?): String {
        return when (status) {
            "WAITING_APPROVAL" -> "is waiting for approval"
            "ACCEPTED" -> "was accepted"
            "PREPARING" -> "is being prepared"
            "READY_FOR_PICKUP", "READY" ->
                if (deliveryType == "DELIVERY") "is ready" else "is ready for pickup"
            "OUT_FOR_DELIVERY" -> "is out for delivery"
            "DELIVERED", "COMPLETED" -> "has been delivered"
            "DENIED" -> "has been denied"
            else -> "status updated: $status"
        }
    }

    // Text for orders list if you use it in adapter
    private fun computeStatusForOrder(order: Order): String {
        val rawStatus = order.status
        val type = order.deliveryType

        return when (rawStatus) {
            "WAITING_APPROVAL" -> "Waiting for seller approval"
            "ACCEPTED" -> "Accepted"
            "PREPARING" -> "Preparing"
            "READY_FOR_PICKUP", "READY" -> "Ready for pickup"
            "OUT_FOR_DELIVERY" ->
                if (type == "PICKUP") "Ready for pickup" else "Out for delivery"
            "DELIVERED", "COMPLETED" -> "Delivered"
            "DENIED" -> "Order denied"
            null, "" -> "Order placed"
            else -> rawStatus
        }
    }

    private fun showStatusPopup(order: Order, statusText: String) {
        val sellerUid = order.items.firstOrNull()?.sellerUid ?: ""

        if (sellerUid.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "Order status updated: $statusText",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val cached = sellerNameCache[sellerUid]
        if (cached != null) {
            Toast.makeText(
                requireContext(),
                "Order from $cached: $statusText",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        db.reference.child("Sellers").child(sellerUid).child("name")
            .get()
            .addOnSuccessListener { snap ->
                val restaurantName = snap.getValue(String::class.java) ?: "your restaurant"
                sellerNameCache[sellerUid] = restaurantName
                Toast.makeText(
                    requireContext(),
                    "Order from $restaurantName: $statusText",
                    Toast.LENGTH_LONG
                ).show()
            }
            .addOnFailureListener {
                Toast.makeText(
                    requireContext(),
                    "Order status updated: $statusText",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    // ==== NOTIFICATIONS ====

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
            val nm = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
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
        // nothing extra; if granted, notifications will work
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun showStatusNotification(order: Order, statusText: String) {
        if (!hasNotificationPermission()) return

        val sellerUid = order.items.firstOrNull()?.sellerUid
        if (sellerUid.isNullOrEmpty()) {
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

        val context = requireContext()

        val intent = Intent(context, MainActivity::class.java).apply {
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
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 100, 200, 300))
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        try {
            with(NotificationManagerCompat.from(context)) {
                notify(notificationId, builder.build())
            }
        } catch (_: SecurityException) {
        }
    }
}
