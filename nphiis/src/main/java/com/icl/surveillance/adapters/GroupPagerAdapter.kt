package com.icl.surveillance.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.icl.surveillance.models.OutputGroup
import com.icl.surveillance.ui.patients.custom.GroupFragment

//class GroupPagerAdapter(
//    fa: FragmentActivity,
//    private val groups: List<OutputGroup>
//) : FragmentStateAdapter(fa) {
//
//    override fun getItemCount(): Int = groups.size
//
//    override fun createFragment(position: Int): Fragment {
//        val group = groups[position]
//        return GroupFragment.newInstance(group)
//    }
//}
class GroupPagerAdapter(
    fa: FragmentActivity,
    private val groups: List<OutputGroup>,
    private val customFragments: List<Pair<String, Fragment>> = emptyList()
) : FragmentStateAdapter(fa) {

    override fun getItemCount(): Int = groups.size + customFragments.size

    override fun createFragment(position: Int): Fragment {
        return if (position < groups.size) {
            val group = groups[position]
            GroupFragment.newInstance(group)
        } else {
            customFragments[position - groups.size].second
        }
    }

    fun getTabTitle(position: Int): String {
        return if (position < groups.size) {
            groups[position].text
        } else {
            customFragments[position - groups.size].first
        }
    }
}

