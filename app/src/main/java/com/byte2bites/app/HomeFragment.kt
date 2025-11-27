package com.byte2bites.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.byte2bites.app.databinding.ActivityHomeBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

/**
 * Home screen fragment for the buyer.
 *
 * Responsibilities:
 * - Display welcome message with user name.
 * - Show list of available sellers (restaurants) in a RecyclerView.
 * - Provide a search box to filter sellers by name.
 * - Provide quick access to the cart via the cart icon.
 */
class HomeFragment : Fragment() {

    private var _b: ActivityHomeBinding? = null
    private val b get() = _b!!

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseDatabase

    private lateinit var adapter: SellerAdapter
    private val sellers = mutableListOf<Seller>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = ActivityHomeBinding.inflate(inflater, container, false)
        return b.root
    }

    /**
     * Called when the view is created:
     * - Initialize Firebase.
     * - Setup RecyclerView and search behavior.
     * - Load greeting and list of sellers.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = FirebaseAuth.getInstance()
        db = FirebaseDatabase.getInstance()

        // Clicking on a seller opens SellerProductsActivity with that seller's UID.
        adapter = SellerAdapter(mutableListOf()) { seller ->
            startActivity(
                Intent(requireContext(), SellerProductsActivity::class.java).apply {
                    putExtra("sellerUid", seller.uid)
                    putExtra("sellerName", seller.name)
                }
            )
        }
        b.rvProducts.layoutManager = LinearLayoutManager(requireContext())
        b.rvProducts.adapter = adapter

        // Cart icon at the top right opens the CartActivity.
        b.ivCart.setOnClickListener {
            startActivity(Intent(requireContext(), CartActivity::class.java))
        }

        setupSearch()
        loadUserGreeting()
        loadSellers()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    // === Top: Welcome Name ===

    /**
     * Loads the buyer profile and sets "Welcome {name}" at the top of the home screen.
     */
    private fun loadUserGreeting() {
        val currentUser = auth.currentUser ?: return

        db.reference.child("Buyers").child(currentUser.uid)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val userProfile = snapshot.getValue(User::class.java)
                    val name = userProfile?.fullName?.takeIf { !it.isNullOrBlank() } ?: "there"
                    b.tvTitleHome.text = "Welcome $name"
                }

                override fun onCancelled(error: DatabaseError) {
                    b.tvTitleHome.text = "Welcome"
                }
            })
    }

    // === Search: restaurants/shops only ===

    /**
     * Hooks a text change listener on the search EditText to filter the sellers list.
     */
    private fun setupSearch() {
        b.etSearch.addTextChangedListener { text ->
            filterSellers(text?.toString().orEmpty())
        }
    }

    /**
     * Filters the in-memory sellers list by name and updates the adapter.
     */
    private fun filterSellers(query: String) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            adapter.submit(sellers)
        } else {
            val filtered = sellers.filter { seller ->
                seller.name?.lowercase()?.contains(q) == true
            }
            adapter.submit(filtered)
        }
    }

    // === Load sellers ===

    /**
     * Reads all sellers from /Sellers in Firebase and builds the list shown in the RecyclerView.
     */
    private fun loadSellers() {
        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(requireContext(), "Not logged in", Toast.LENGTH_SHORT).show()
            return
        }

        db.reference.child("Sellers")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(root: DataSnapshot) {
                    val list = ArrayList<Seller>()
                    for (sellerSnap in root.children) {
                        val uid = sellerSnap.key ?: continue
                        val name = sellerSnap.child("name").getValue(String::class.java)
                        val email = sellerSnap.child("email").getValue(String::class.java)
                        val phone = sellerSnap.child("phone").getValue(String::class.java)
                        val profileImageUrl =
                            sellerSnap.child("profileImageUrl").getValue(String::class.java)

                        list.add(
                            Seller(
                                uid = uid,
                                name = name,
                                email = email,
                                phone = phone,
                                profileImageUrl = profileImageUrl
                            )
                        )
                    }
                    sellers.clear()
                    sellers.addAll(list)

                    // Reuse current search box text to filter the newly loaded list.
                    val q = b.etSearch.text?.toString().orEmpty()
                    filterSellers(q)

                    if (sellers.isEmpty()) {
                        Toast.makeText(
                            requireContext(),
                            "No restaurants found.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(
                        requireContext(),
                        "Failed to load restaurants: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
    }
}
