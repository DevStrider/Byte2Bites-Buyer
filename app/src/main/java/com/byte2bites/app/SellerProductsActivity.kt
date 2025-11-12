package com.byte2bites.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.byte2bites.app.databinding.ActivitySellerProductsBinding
import com.google.firebase.database.*

class SellerProductsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySellerProductsBinding
    private lateinit var db: FirebaseDatabase
    private lateinit var adapter: ProductAdapter
    private val products = mutableListOf<Product>()

    private var sellerUid: String = ""
    private var sellerName: String? = null
    private var deliveryFeeCents: Long = 0L

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

        loadDeliveryFee()
        loadProducts()
    }

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

    // helpers

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
}
