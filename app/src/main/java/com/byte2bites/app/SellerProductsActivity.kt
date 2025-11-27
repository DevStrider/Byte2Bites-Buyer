package com.byte2bites.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.byte2bites.app.databinding.ActivitySellerProductsBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlin.math.*

/**
 * Activity that displays all products for a particular seller (restaurant) to the buyer.
 *
 * Responsibilities:
 * - Fetch seller products from Firebase under /Sellers/{sellerUid}/products.
 * - Display products in a grid using ProductAdapter.
 * - Show a dynamic delivery fee based on distance between buyer and seller.
 * - Observe buyer's cart and show a bottom "View cart" bar when items exist.
 */
class SellerProductsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySellerProductsBinding
    private lateinit var db: FirebaseDatabase
    private lateinit var adapter: ProductAdapter
    private val products = mutableListOf<Product>()

    // The seller we are currently viewing.
    private var sellerUid: String = ""
    private var sellerName: String? = null

    // Buyer auth (used to read buyer-specific cart and address).
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    // Cart bar observer (realtime listener handle + reference).
    private var cartListener: ValueEventListener? = null
    private var cartDbRef: DatabaseReference? = null

    // Same delivery fee brackets as CartActivity (in cents).
    // These constants define distance-based delivery pricing.
    private val DELIVERY_FEE_0_TO_10_KM_CENTS = 1500L  // 15.00
    private val DELIVERY_FEE_10_TO_20_KM_CENTS = 2500L // 25.00
    private val DELIVERY_FEE_20_TO_30_KM_CENTS = 3500L // 35.00

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySellerProductsBinding.inflate(layoutInflater)
        setContentView(b.root)

        db = FirebaseDatabase.getInstance()

        // Seller identity and display name passed via Intent extras.
        sellerUid = intent.getStringExtra("sellerUid") ?: ""
        sellerName = intent.getStringExtra("sellerName")

        b.tvTitle.text = sellerName ?: "Restaurant"

        // Product grid showing all items from this seller.
        adapter = ProductAdapter(mutableListOf()) { product ->
            // Navigate to product details when an item is clicked.
            val intent = Intent(this, ProductDetailsActivity::class.java).apply {
                putExtra("productID", product.productID)
                putExtra("name", product.name)
                putExtra("price", product.price)
                putExtra("imageUrl", product.imageUrl)
                putExtra("description", product.description)
                putExtra("sellerUid", sellerUid)
            }
            startActivity(intent)
        }

        b.rvProducts.layoutManager = GridLayoutManager(this, 2)
        b.rvProducts.adapter = adapter

        // Back arrow closes this screen.
        b.ivBack.setOnClickListener { finish() }

        // Tapping the bar opens the cart screen (shared cart for all sellers).
        b.cartBar.setOnClickListener {
            startActivity(Intent(this, CartActivity::class.java))
        }

        // Distance-based delivery fee label (dependent on buyer address + seller location).
        loadDynamicDeliveryFee()

        // Product listing for this seller.
        loadProducts()

        // Observe the buyer's cart to decide when to show/hide the cart bar.
        observeCartBar()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up cart listener to avoid memory leaks or extra callbacks.
        cartDbRef?.let { ref ->
            cartListener?.let { listener ->
                ref.removeEventListener(listener)
            }
        }
    }

    // ================== DYNAMIC DELIVERY FEE (distance-based) ==================

    /**
     * Calculates and displays a dynamic delivery fee based on:
     * - Buyer's saved address (lat/lng in /Buyers/{uid}/address).
     * - Seller's location (lat/lng in /Sellers/{sellerUid}).
     *
     * If any information is missing, the TextView explains what the user should do.
     */
    private fun loadDynamicDeliveryFee() {
        if (sellerUid.isEmpty()) {
            b.tvDeliveryFee.text = "Delivery: -"
            return
        }

        val uid = auth.currentUser?.uid
        if (uid == null) {
            b.tvDeliveryFee.text = "Login to see delivery price"
            return
        }

        val buyerRef = db.reference.child("Buyers").child(uid)

        // 1) Load buyer address (with lat/lng).
        buyerRef.child("address").get()
            .addOnSuccessListener { addrSnap ->
                val address = addrSnap.getValue(Address::class.java)
                val buyerLat = address?.latitude
                val buyerLng = address?.longitude

                if (address == null) {
                    b.tvDeliveryFee.text = "Add address to see delivery price"
                    return@addOnSuccessListener
                }

                if (buyerLat == null || buyerLng == null) {
                    b.tvDeliveryFee.text = "Choose location on map to see delivery price"
                    return@addOnSuccessListener
                }

                // 2) Load seller location.
                db.reference.child("Sellers").child(sellerUid).get()
                    .addOnSuccessListener { sellerSnap ->
                        val sLatAny = sellerSnap.child("latitude").value
                        val sLngAny = sellerSnap.child("longitude").value

                        val sellerLat = sLatAny?.toString()?.toDoubleOrNull()
                        val sellerLng = sLngAny?.toString()?.toDoubleOrNull()

                        if (sellerLat == null || sellerLng == null) {
                            b.tvDeliveryFee.text = "Delivery: seller location missing"
                            return@addOnSuccessListener
                        }

                        // 3) Distance + fee calculation.
                        val distKm = haversineKm(buyerLat, buyerLng, sellerLat, sellerLng)
                        val deliveryFeeCents = calculateDeliveryFeeCents(distKm)

                        when {
                            deliveryFeeCents < 0L -> {
                                // Negative fee means delivery is not available for this distance.
                                b.tvDeliveryFee.text =
                                    "Delivery not available to your address (> 30 km)"
                            }
                            else -> {
                                // Show something like: "Delivery: $25.00 (7.3 km)".
                                val feeText = formatCurrency(deliveryFeeCents)
                                val distText = formatDistanceKm(distKm)
                                b.tvDeliveryFee.text = "Delivery: $feeText ($distText)"
                            }
                        }
                    }
                    .addOnFailureListener {
                        b.tvDeliveryFee.text = "Delivery: -"
                    }
            }
            .addOnFailureListener {
                b.tvDeliveryFee.text = "Delivery: -"
            }
    }

    // ================== PRODUCTS ==================

    /**
     * Loads the products for the current seller from:
     * /Sellers/{sellerUid}/products
     * and displays them in the RecyclerView using ProductAdapter.
     */
    private fun loadProducts() {
        if (sellerUid.isEmpty()) return

        db.reference.child("Sellers").child(sellerUid).child("products")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val list = ArrayList<Product>()
                    for (pSnap in snapshot.children) {
                        // Read product properties from the snapshot; fallback to key for productID.
                        val productID =
                            pSnap.child("productID").getValue(String::class.java)
                                ?: pSnap.key ?: ""

                        val name = pSnap.child("name").getValue(String::class.java)
                        val price = pSnap.child("price").getValue(String::class.java)
                        val description =
                            pSnap.child("description").getValue(String::class.java)
                        val imageUrl =
                            pSnap.child("imageUrl").getValue(String::class.java)

                        val p = Product(
                            productID = productID,
                            name = name,
                            price = price,
                            description = description,
                            imageUrl = imageUrl,
                            sellerUid = sellerUid
                        )
                        list.add(p)
                    }
                    products.clear()
                    products.addAll(list)
                    adapter.submit(products)
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(
                        this@SellerProductsActivity,
                        error.message,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }

    // ================== CART BAR (View cart) ==================

    /**
     * Attaches a realtime listener on the buyer's "/cart" node.
     * The cart bar is only shown when:
     * - The cart belongs to this specific seller, and
     * - There is at least one item with quantity > 0.
     */
    private fun observeCartBar() {
        val uid = auth.currentUser?.uid ?: run {
            hideCartBar()
            return
        }

        val buyerRef = db.reference.child("Buyers").child(uid)
        val cartRef = buyerRef.child("cart")
        cartDbRef = cartRef

        // Remove old listener if present before adding a new one.
        cartListener?.let { cartRef.removeEventListener(it) }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // First check which seller the cart belongs to.
                buyerRef.child("cartMeta").child("sellerUid")
                    .get()
                    .addOnSuccessListener { metaSnap ->
                        val sellerInCart = metaSnap.getValue(String::class.java)

                        if (sellerInCart == null || sellerInCart != sellerUid) {
                            // Cart is empty or belongs to another restaurant -> hide bar.
                            hideCartBar()
                            return@addOnSuccessListener
                        }

                        // Build list of cart items.
                        val list = mutableListOf<CartItem>()
                        for (child in snapshot.children) {
                            val item = child.getValue(CartItem::class.java)
                            if (item != null && item.quantity > 0) {
                                list.add(item)
                            }
                        }

                        if (list.isEmpty()) {
                            hideCartBar()
                        } else {
                            val totalItems = list.sumOf { it.quantity }
                            val itemsTotalCents = totalCents(list)
                            showCartBar(totalItems, itemsTotalCents)
                        }
                    }
            }

            override fun onCancelled(error: DatabaseError) {
                hideCartBar()
            }
        }

        cartRef.addValueEventListener(listener)
        cartListener = listener
    }

    /**
     * Hides the bottom cart bar when no valid cart exists for this seller.
     */
    private fun hideCartBar() {
        b.cartBar.visibility = View.GONE
    }

    /**
     * Updates the bottom cart bar with the current number of items and subtotal.
     */
    private fun showCartBar(itemCount: Int, itemsTotalCents: Long) {
        b.cartBar.visibility = View.VISIBLE

        val label = if (itemCount == 1) {
            "View cart (1 item)"
        } else {
            "View cart ($itemCount items)"
        }
        b.tvCartLabel.text = label
        b.tvCartSummary.text = formatCurrency(itemsTotalCents)
    }

    // ================== HELPERS (price, distance, fee) ==================

    /**
     * Parses a string price (assumed to contain digits representing whole units)
     * and returns its value in cents.
     * Example:
     *  - "25" -> 2500 (25.00)
     */
    private fun parsePrice(priceString: String?): Long {
        if (priceString.isNullOrBlank()) return 0L
        val digitsOnly = priceString.filter { it.isDigit() }
        if (digitsOnly.isEmpty()) return 0L
        val units = digitsOnly.toLongOrNull() ?: return 0L
        return units * 100L
    }

    /**
     * Converts a value in cents to a formatted currency string using
     * the app's currency symbol.
     */
    private fun formatCurrency(cents: Long): String {
        val symbol = getString(R.string.currency_symbol)
        val whole = cents / 100
        val frac = (cents % 100).toString().padStart(2, '0')
        return "$symbol$whole.$frac"
    }

    /**
     * Computes the total in cents for a list of CartItem objects using parsePrice().
     */
    private fun totalCents(items: List<CartItem>): Long =
        items.sumOf { parsePrice(it.price) * it.quantity }

    /**
     * Standard Haversine formula to compute distance in kilometers between two geo points.
     */
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

    /**
     * Maps a continuous distance in km to a discrete delivery fee in cents.
     * Returns -1 if distance exceeds the supported radius (no delivery).
     */
    private fun calculateDeliveryFeeCents(distanceKm: Double): Long {
        return when {
            distanceKm <= 10.0 -> DELIVERY_FEE_0_TO_10_KM_CENTS
            distanceKm <= 20.0 -> DELIVERY_FEE_10_TO_20_KM_CENTS
            distanceKm <= 30.0 -> DELIVERY_FEE_20_TO_30_KM_CENTS
            else -> -1L // means "no delivery"
        }
    }

    /**
     * Formats distance in kilometers with one decimal place.
     */
    private fun formatDistanceKm(distanceKm: Double): String {
        return String.format("%.1f km", distanceKm)
    }
}
