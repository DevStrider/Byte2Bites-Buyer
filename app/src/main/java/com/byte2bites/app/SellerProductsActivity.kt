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

class SellerProductsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySellerProductsBinding
    private lateinit var db: FirebaseDatabase
    private lateinit var adapter: ProductAdapter
    private val products = mutableListOf<Product>()

    private var sellerUid: String = ""
    private var sellerName: String? = null
    private var deliveryFeeCents: Long = 0L

    // NEW: for cart bar
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private var cartListener: ValueEventListener? = null
    private var cartDbRef: DatabaseReference? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySellerProductsBinding.inflate(layoutInflater)
        setContentView(b.root)

        db = FirebaseDatabase.getInstance()

        sellerUid = intent.getStringExtra("sellerUid") ?: ""
        sellerName = intent.getStringExtra("sellerName")

        b.tvTitle.text = sellerName ?: "Restaurant"

        adapter = ProductAdapter(mutableListOf()) { product ->
            // open product details
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

        loadDeliveryFee()
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

    // === DELIVERY FEE & PRODUCTS ===

    private fun loadDeliveryFee() {
        if (sellerUid.isEmpty()) return

        db.reference.child("Sellers").child(sellerUid).child("deliveryInfo")
            .get()
            .addOnSuccessListener { snap ->
                val feeStr = snap.getValue(String::class.java) ?: "0"
                deliveryFeeCents = parsePrice(feeStr)
                b.tvDeliveryFee.text = "Delivery fee: ${formatCurrency(deliveryFeeCents)}"
            }
            .addOnFailureListener {
                b.tvDeliveryFee.text = "Delivery fee: -"
            }
    }

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

    // === CART BAR (like Talabat "View cart") ===

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

    // === PRICE HELPERS ===

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
}
