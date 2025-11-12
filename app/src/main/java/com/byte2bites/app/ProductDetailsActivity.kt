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

    private lateinit var productItem: CartItem   // what we’ll add to cart

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
        b.tvPrice.text = price
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

        b.btnAddToCart.setOnClickListener { prepareAddToCart() }
    }

    /** Enforce single-restaurant cart with confirm dialog when switching */
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

            if (currentSeller.isNullOrEmpty() ||
                currentSeller == productItem.sellerUid ||
                productItem.sellerUid.isEmpty()
            ) {
                addItemToCart(cartRef, metaRef)
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Start new cart?")
                    .setMessage("Starting a new order will clear your current cart.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Start") { _, _ ->
                        cartRef.removeValue().addOnCompleteListener {
                            metaRef.child("sellerUid").setValue(productItem.sellerUid)
                            addItemToCart(cartRef, metaRef)
                        }
                    }
                    .show()
            }
        }.addOnFailureListener {
            addItemToCart(cartRef, metaRef)
        }
    }

    private fun addItemToCart(
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
            val newItem = productItem.copy(quantity = currentQty + 1)
            itemRef.setValue(newItem).addOnSuccessListener {
                Toast.makeText(this, "Added to cart", Toast.LENGTH_SHORT).show()
                finish()
            }.addOnFailureListener { e ->
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
