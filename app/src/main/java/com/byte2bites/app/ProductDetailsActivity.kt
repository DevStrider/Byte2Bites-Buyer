package com.byte2bites.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.byte2bites.app.databinding.ActivityProductDetailsBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class ProductDetailsActivity : AppCompatActivity() {

    private lateinit var b: ActivityProductDetailsBinding
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseDatabase.getInstance() }

    private lateinit var product: CartItem

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityProductDetailsBinding.inflate(layoutInflater)
        setContentView(b.root)

        val name = intent.getStringExtra("name") ?: ""
        val price = intent.getStringExtra("price") ?: ""
        val imageUrl = intent.getStringExtra("imageUrl") ?: ""
        val description = intent.getStringExtra("description") ?: ""
        val productID = intent.getStringExtra("productID") ?: ""
        val sellerUid = intent.getStringExtra("sellerUid") ?: ""

        b.tvName.text = name
        b.tvPrice.text = price
        b.tvDescription.text = description
        Glide.with(this).load(imageUrl)
            .placeholder(R.drawable.ic_profile_placeholder)
            .into(b.ivImage)

        product = CartItem(
            productID = productID,
            name = name,
            price = price,
            imageUrl = imageUrl,
            quantity = 1,
            sellerUid = sellerUid
        )

        b.btnAddToCart.setOnClickListener { prepareAddToCart() }
    }

    /** Enforce single-restaurant cart */
    private fun prepareAddToCart() {
        val uid = auth.currentUser?.uid ?: run {
            Toast.makeText(this, "You are not logged in", Toast.LENGTH_SHORT).show()
            return
        }

        val buyerRef = db.reference.child("Buyers").child(uid)
        val cartRef = buyerRef.child("cart")
        val metaRef = buyerRef.child("cartMeta")

        metaRef.child("sellerUid").get().addOnSuccessListener { metaSnap ->
            val currentSeller = metaSnap.getValue(String::class.java)

            // 1) No seller yet, or same seller -> just add
            if (currentSeller.isNullOrEmpty() ||
                currentSeller == product.sellerUid ||
                product.sellerUid.isEmpty()
            ) {
                addItemToCart(cartRef, metaRef)
            } else {
                // 2) Different seller -> ask user
                AlertDialog.Builder(this)
                    .setTitle("Start new cart?")
                    .setMessage("Starting a new order will clear your current cart.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Start") { _, _ ->
                        // Clear old cart then add
                        cartRef.removeValue().addOnCompleteListener {
                            metaRef.child("sellerUid").setValue(product.sellerUid)
                            addItemToCart(cartRef, metaRef)
                        }
                    }
                    .show()
            }
        }.addOnFailureListener {
            // if meta read fails, fall back to normal add
            addItemToCart(cartRef, metaRef)
        }
    }

    private fun addItemToCart(
        cartRef: com.google.firebase.database.DatabaseReference,
        metaRef: com.google.firebase.database.DatabaseReference
    ) {
        val uid = auth.currentUser?.uid ?: return

        // ensure meta sellerUid is set
        if (!product.sellerUid.isNullOrEmpty()) {
            metaRef.child("sellerUid").setValue(product.sellerUid)
        }

        val itemRef = cartRef.child(product.productID)

        itemRef.get().addOnSuccessListener { snap ->
            val currentQty = snap.child("quantity").getValue(Int::class.java) ?: 0
            val newItem = product.copy(quantity = currentQty + 1)
            itemRef.setValue(newItem).addOnSuccessListener {
                Toast.makeText(this, "Added to cart", Toast.LENGTH_SHORT).show()
                finish()
            }.addOnFailureListener { e ->
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
