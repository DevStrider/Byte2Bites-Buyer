package com.byte2bites.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.byte2bites.app.databinding.ActivityProductDetailsBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class ProductDetailsActivity : AppCompatActivity() {

    private lateinit var b: ActivityProductDetailsBinding
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseDatabase.getInstance() }

    private lateinit var productItem: CartItem   // item we add/update in cart
    private var currentQuantity: Int = 0

    // 🔹 Maximum quantity available in stock for this product
    private var maxAvailableQuantity: Int = Int.MAX_VALUE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityProductDetailsBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Back arrow
        b.ivBack.setOnClickListener { finish() }

        // Read extras from intent
        val name = intent.getStringExtra("name") ?: ""
        val price = intent.getStringExtra("price") ?: ""
        val imageUrl = intent.getStringExtra("imageUrl") ?: ""
        val description = intent.getStringExtra("description") ?: ""
        val productID = intent.getStringExtra("productID") ?: ""
        val sellerUid = intent.getStringExtra("sellerUid") ?: ""

        // Bind UI
        b.tvName.text = name
        val symbol = getString(R.string.currency_symbol)
        b.tvPrice.text = "$symbol${price ?: ""}"
        b.tvDescription.text = description
        Glide.with(this).load(imageUrl)
            .placeholder(R.drawable.ic_profile_placeholder)
            .into(b.ivImage)

        productItem = CartItem(
            productID = productID,
            name = name,
            price = price,
            imageUrl = imageUrl,
            quantity = 1,
            sellerUid = sellerUid
        )

        // Load stock for this product from Sellers/{sellerUid}/products/{productID}/quantity
        loadAvailableQuantity()

        // Click listeners
        b.btnAddToCart.setOnClickListener { prepareAddToCart() }
        b.btnPlus.setOnClickListener { increaseQuantity() }
        b.btnMinus.setOnClickListener { decreaseQuantity() }

        // Load current quantity (if already in cart)
        loadCurrentQuantity()
    }

    // === UI helpers ===

    private fun showAddButton() {
        currentQuantity = 0
        b.btnAddToCart.visibility = android.view.View.VISIBLE
        b.qtyContainer.visibility = android.view.View.GONE
    }

    private fun showQuantityControls(qty: Int) {
        currentQuantity = qty
        b.tvQuantity.text = qty.toString()
        b.btnAddToCart.visibility = android.view.View.GONE
        b.qtyContainer.visibility = android.view.View.VISIBLE
    }

    // === Load existing quantity from cart if present ===

    private fun loadCurrentQuantity() {
        val uid = auth.currentUser?.uid ?: run {
            showAddButton()
            return
        }

        if (productItem.productID.isEmpty()) {
            showAddButton()
            return
        }

        val itemRef = db.reference
            .child("Buyers")
            .child(uid)
            .child("cart")
            .child(productItem.productID)

        itemRef.get().addOnSuccessListener { snap ->
            val qty = snap.child("quantity").getValue(Int::class.java) ?: 0
            if (qty > 0) {
                showQuantityControls(qty)
            } else {
                showAddButton()
            }
        }.addOnFailureListener {
            showAddButton()
        }
    }

    // === Load available stock from seller node ===

    private fun loadAvailableQuantity() {
        val sellerUid = productItem.sellerUid ?: return
        val productId = productItem.productID
        if (productId.isEmpty()) return

        db.reference
            .child("Sellers")
            .child(sellerUid)
            .child("products")
            .child(productId)
            .child("quantity")
            .get()
            .addOnSuccessListener { snap ->
                val qtyStr = snap.getValue(String::class.java) ?: "0"
                maxAvailableQuantity = qtyStr.toIntOrNull() ?: 0

                if (maxAvailableQuantity <= 0) {
                    Toast.makeText(
                        this,
                        "This product is currently out of stock",
                        Toast.LENGTH_LONG
                    ).show()
                }

                // If user already had a higher qty, clamp it down
                if (currentQuantity > maxAvailableQuantity) {
                    currentQuantity = maxAvailableQuantity.coerceAtLeast(0)
                    if (currentQuantity == 0) {
                        showAddButton()
                    } else {
                        showQuantityControls(currentQuantity)
                    }
                }
            }
            .addOnFailureListener {
                // If we can't load it, treat as "no limit" to avoid blocking
                maxAvailableQuantity = Int.MAX_VALUE
            }
    }

    // === Add first item (or handle cross-restaurant cart) ===

    /** Enforce single-restaurant cart with confirm dialog when switching */
    private fun prepareAddToCart() {
        val uid = auth.currentUser?.uid ?: run {
            Toast.makeText(this, "You are not logged in", Toast.LENGTH_SHORT).show()
            return
        }

        if (productItem.productID.isEmpty()) {
            Toast.makeText(this, "Product not found", Toast.LENGTH_SHORT).show()
            return
        }

        // 🔒 If out of stock, don't allow adding
        if (maxAvailableQuantity <= 0) {
            Toast.makeText(this, "This product is out of stock", Toast.LENGTH_LONG).show()
            return
        }

        val buyerRef = db.reference.child("Buyers").child(uid)
        val cartRef = buyerRef.child("cart")
        val metaRef = buyerRef.child("cartMeta")

        metaRef.child("sellerUid").get().addOnSuccessListener { metaSnap ->
            val currentSeller = metaSnap.getValue(String::class.java)

            if (currentSeller.isNullOrEmpty() ||
                currentSeller == productItem.sellerUid ||
                productItem.sellerUid.isNullOrEmpty()
            ) {
                // same seller or empty cart → just add
                addFirstItemToCart(cartRef, metaRef)
            } else {
                // different seller → warn & optionally clear
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Start new cart?")
                    .setMessage("Starting a new order will clear your current cart.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Start") { _, _ ->
                        cartRef.removeValue().addOnCompleteListener {
                            metaRef.child("sellerUid").setValue(productItem.sellerUid)
                            addFirstItemToCart(cartRef, metaRef)
                        }
                    }
                    .show()
            }
        }.addOnFailureListener {
            // if metadata fails to load, just try adding
            addFirstItemToCart(cartRef, metaRef)
        }
    }

    /** Adds the first item (or increments if already in this cart) and shows quantity controls */
    private fun addFirstItemToCart(
        cartRef: com.google.firebase.database.DatabaseReference,
        metaRef: com.google.firebase.database.DatabaseReference
    ) {
        val uid = auth.currentUser?.uid ?: return

        if (!productItem.sellerUid.isNullOrEmpty()) {
            metaRef.child("sellerUid").setValue(productItem.sellerUid)
        }

        val itemRef = cartRef.child(productItem.productID)
        itemRef.get().addOnSuccessListener { snap ->
            val currentQty = snap.child("quantity").getValue(Int::class.java) ?: 0
            val newQty = currentQty + 1

            // 🔒 Enforce stock limit
            if (maxAvailableQuantity != Int.MAX_VALUE && newQty > maxAvailableQuantity) {
                Toast.makeText(
                    this,
                    "Only $maxAvailableQuantity available in stock",
                    Toast.LENGTH_SHORT
                ).show()
                return@addOnSuccessListener
            }

            val newItem = productItem.copy(quantity = newQty)
            itemRef.setValue(newItem).addOnSuccessListener {
                Toast.makeText(this, "Added to cart", Toast.LENGTH_SHORT).show()
                showQuantityControls(newQty)
            }.addOnFailureListener { e ->
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // === Plus / minus actions ===

    private fun increaseQuantity() {
        val uid = auth.currentUser?.uid ?: run {
            Toast.makeText(this, "You are not logged in", Toast.LENGTH_SHORT).show()
            return
        }

        // 🔒 If out of stock, don't allow increment at all
        if (maxAvailableQuantity <= 0) {
            Toast.makeText(this, "This product is out of stock", Toast.LENGTH_LONG).show()
            return
        }

        val itemRef = db.reference
            .child("Buyers")
            .child(uid)
            .child("cart")
            .child(productItem.productID)

        itemRef.get().addOnSuccessListener { snap ->
            val currentQty = snap.child("quantity").getValue(Int::class.java) ?: 0
            val newQty = currentQty + 1

            // 🔒 Enforce max available
            if (maxAvailableQuantity != Int.MAX_VALUE && newQty > maxAvailableQuantity) {
                Toast.makeText(
                    this,
                    "Only $maxAvailableQuantity available in stock",
                    Toast.LENGTH_SHORT
                ).show()
                return@addOnSuccessListener
            }

            val newItem = productItem.copy(quantity = newQty)
            itemRef.setValue(newItem).addOnSuccessListener {
                showQuantityControls(newQty)
            }.addOnFailureListener { e ->
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            }
        }.addOnFailureListener { e ->
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun decreaseQuantity() {
        val uid = auth.currentUser?.uid ?: run {
            Toast.makeText(this, "You are not logged in", Toast.LENGTH_SHORT).show()
            return
        }

        val itemRef = db.reference
            .child("Buyers")
            .child(uid)
            .child("cart")
            .child(productItem.productID)

        itemRef.get().addOnSuccessListener { snap ->
            val currentQty = snap.child("quantity").getValue(Int::class.java) ?: 0
            val newQty = (currentQty - 1).coerceAtLeast(0)

            if (newQty == 0) {
                // Remove item from cart and show Add button again
                itemRef.removeValue().addOnSuccessListener {
                    showAddButton()
                }.addOnFailureListener { e ->
                    Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                }
            } else {
                val newItem = productItem.copy(quantity = newQty)
                itemRef.setValue(newItem).addOnSuccessListener {
                    showQuantityControls(newQty)
                }.addOnFailureListener { e ->
                    Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                }
            }
        }.addOnFailureListener { e ->
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }
}
