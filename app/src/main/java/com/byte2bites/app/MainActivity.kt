package com.byte2bites.app

import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.byte2bites.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        supportActionBar?.hide()

        setupViewPager()
        setupBottomNav()
    }

    private fun setupViewPager() {
        val adapter = MainPagerAdapter(this)
        b.viewPager.adapter = adapter
        b.viewPager.offscreenPageLimit = 2
        b.viewPager.orientation = ViewPager2.ORIENTATION_HORIZONTAL

        // When user swipes, update bottom nav highlight
        b.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateBottomNav(position)
            }
        })
    }

    private fun setupBottomNav() {
        b.navHome.setOnClickListener {
            b.viewPager.setCurrentItem(0, true)
        }
        b.navOrders.setOnClickListener {
            b.viewPager.setCurrentItem(1, true)
        }
        b.navProfile.setOnClickListener {
            b.viewPager.setCurrentItem(2, true)
        }

        // Default tab = Home
        updateBottomNav(0)
    }

    private fun updateBottomNav(position: Int) {
        val primary = getColor(R.color.bb_primary_blue)
        val secondary = getColor(R.color.text_secondary_dark)

        fun setTab(selected: Boolean, icon: ImageView, label: TextView) {
            val color = if (selected) primary else secondary
            icon.setColorFilter(color)
            label.setTextColor(color)
        }

        setTab(
            position == 0,
            b.navHomeIcon,
            b.navHomeLabel
        )
        setTab(
            position == 1,
            b.navOrdersIcon,
            b.navOrdersLabel
        )
        setTab(
            position == 2,
            b.navProfileIcon,
            b.navProfileLabel
        )
    }
}
