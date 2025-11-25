package com.byte2bites.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.byte2bites.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupViewPager()
        setupBottomNav()

        // Default tab when app opens normally
        if (savedInstanceState == null) {
            selectTab(0) // Home
        }

        // Handle navigation when launched from a notification
        handleNavigationIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleNavigationIntent(intent)
        }
    }

    // =========== Navigation from notification ===========

    private fun handleNavigationIntent(intent: Intent) {
        when (intent.getStringExtra("navigate_to")) {
            "orders" -> selectTab(1)
            "profile" -> selectTab(2)
            "home" -> selectTab(0)
        }
    }

    // =========== ViewPager2 setup ===========

    private fun setupViewPager() {
        val pagerAdapter = MainPagerAdapter(this)
        b.viewPager.adapter = pagerAdapter
        b.viewPager.isUserInputEnabled = true   // enable swiping

        // When user swipes, update bottom nav highlight
        b.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateBottomNav(position)
            }
        })
    }

    // =========== Custom bottom nav (LinearLayout) ===========

    private fun setupBottomNav() {
        b.navHome.setOnClickListener { selectTab(0) }
        b.navOrders.setOnClickListener { selectTab(1) }
        b.navProfile.setOnClickListener { selectTab(2) }
    }

    private fun selectTab(index: Int) {
        b.viewPager.currentItem = index
        updateBottomNav(index)
    }

    private fun updateBottomNav(selectedIndex: Int) {
        val activeColor = ContextCompat.getColor(this, R.color.bb_primary_blue)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_secondary_dark)

        fun setTab(active: Boolean,
                   iconView: android.widget.ImageView,
                   labelView: android.widget.TextView) {
            val color = if (active) activeColor else inactiveColor
            iconView.setColorFilter(color)
            labelView.setTextColor(color)
        }

        setTab(
            active = selectedIndex == 0,
            iconView = b.navHomeIcon,
            labelView = b.navHomeLabel
        )

        setTab(
            active = selectedIndex == 1,
            iconView = b.navOrdersIcon,
            labelView = b.navOrdersLabel
        )

        setTab(
            active = selectedIndex == 2,
            iconView = b.navProfileIcon,
            labelView = b.navProfileLabel
        )
    }
}
