package com.byte2bites.app

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * ViewPager2 adapter that provides the 3 main buyer fragments:
 * - position 0 → HomeFragment
 * - position 1 → OrdersFragment
 * - position 2 → ProfileFragment
 */
class MainPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 3

    /**
     * Returns the fragment instance that should be displayed for the given position.
     */
    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> HomeFragment()
            1 -> OrdersFragment()
            2 -> ProfileFragment()
            else -> HomeFragment()
        }
    }
}
