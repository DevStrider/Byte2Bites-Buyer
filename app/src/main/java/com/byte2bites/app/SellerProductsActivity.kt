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

class SellerProductsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySellerProductsBinding
    private lateinit var db: FirebaseDatabase
    private lateinit var adapter: ProductAdapter
    private val products = mutableListOf<Product>()

    private var sellerUid: String = ""
    private var sellerName: String? = null

    // Buyer auth
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    // Cart bar observer
    private var cartListener: ValueEventListener? = null
    private var cartDbRef: DatabaseReference? = null

    // Same delivery fee brackets as CartActivity (in cents)
    // Example values: change them to match your CartActivity constants
    private val DELIVERY_FEE_0_TO_10_KM_CENTS = 1500L  // 15.00
    private val DELIVERY_FEE_10_TO_20_KM_CENTS = 2500L // 25.00
    private val DELIVERY_FEE_20_TO_30_KM_CENTS = 3500L // 35.00

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySellerProductsBinding.inflate(layoutInflater)
        setContentView(b.root)

        db = FirebaseDatabase.getInstance()

        sellerUid = intent.getStringExtra("sellerUid") ?: ""
        sellerName = intent.getStringExtra("sellerName")

        b.tvTitle.text = sellerName ?: "Restaurant"

        adapter = ProductAdapter(mutableListOf()) { product ->
            // Open product details
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

        b.ivBack.setOnClickListener { finish() }

        // Tapping the bar opens the cart
        b.cartBar.setOnClickListener {
            startActivity(Intent(this, CartActivity::class.java))
        }

        loadDynamicDeliveryFee()
        loadProducts()
        observeCartBar()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up cart listener
        cartDbRef?.let { ref ->
            cartListener?.let { listener ->
                ref.removeEventListener(listener)
            }
        }
    }

    // ================== DYNAMIC DELIVERY FEE (distance-based) ==================

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

        // 1) Load buyer address (with lat/lng)
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

                // 2) Load seller location
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

                        // 3) Distance + fee
                        val distKm = haversineKm(buyerLat, buyerLng, sellerLat, sellerLng)
                        val deliveryFeeCents = calculateDeliveryFeeCents(distKm)

                        when {
                            deliveryFeeCents < 0L -> {
                                b.tvDeliveryFee.text =
                                    "Delivery not available to your address (> 30 km)"
                            }
                            else -> {
                                // Show something like: "Delivery: $25.00 (7.3 km)"
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

    private fun loadProducts() {
        if (sellerUid.isEmpty()) return

        db.reference.child("Sellers").child(sellerUid).child("products")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val list = ArrayList<Product>()
                    for (pSnap in snapshot.children) {
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

    private fun observeCartBar() {
        val uid = auth.currentUser?.uid ?: run {
            hideCartBar()
            return
        }

        val buyerRef = db.reference.child("Buyers").child(uid)
        val cartRef = buyerRef.child("cart")
        cartDbRef = cartRef

        // remove old listener if present
        cartListener?.let { cartRef.removeEventListener(it) }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // First check which seller the cart belongs to
                buyerRef.child("cartMeta").child("sellerUid")
                    .get()
                    .addOnSuccessListener { metaSnap ->
                        val sellerInCart = metaSnap.getValue(String::class.java)

                        if (sellerInCart == null || sellerInCart != sellerUid) {
                            // Cart is empty or belongs to another restaurant -> hide bar
                            hideCartBar()
                            return@addOnSuccessListener
                        }

                        // Build list of cart items
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

    private fun hideCartBar() {
        b.cartBar.visibility = View.GONE
    }

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

    private fun parsePrice(priceString: String?): Long {
        if (priceString.isNullOrBlank()) return 0L
        val digitsOnly = priceString.filter { it.isDigit() }
        if (digitsOnly.isEmpty()) return 0L
        val units = digitsOnly.toLongOrNull() ?: return 0L
        return units * 100L
    }

    private fun formatCurrency(cents: Long): String {
        val whole = cents / 100
        val frac = (cents % 100).toString().padStart(2, '0')
        return "$$whole.$frac"
    }

    private fun totalCents(items: List<CartItem>): Long =
        items.sumOf { parsePrice(it.price) * it.quantity }

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

    private fun formatDistanceKm(distanceKm: Double): String {
        return String.format("%.1f km", distanceKm)
    }
}
