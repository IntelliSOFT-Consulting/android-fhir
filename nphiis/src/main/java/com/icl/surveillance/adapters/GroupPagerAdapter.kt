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
/**
 * Summary tabs: the form's sections, then [customFragments]. With [combinedTitle] the sections
 * are shown together on one scrolling tab of that name instead of one tab each.
 */
class GroupPagerAdapter(
    fa: FragmentActivity,
    private val groups: List<OutputGroup>,
    private val customFragments: List<Pair<String, Fragment>> = emptyList(),
    private val combinedTitle: String? = null,
    /** Changes whenever the tabs are rebuilt with new data; makes every tab a new fragment. */
    private val generation: Long = 0L,
) : FragmentStateAdapter(fa) {

    override fun getItemId(position: Int): Long = generation * 1000L + position

    override fun containsItem(itemId: Long): Boolean =
        Math.floorDiv(itemId, 1000L) == generation && Math.floorMod(itemId, 1000L) < itemCount

    private val sectionTabs: Int
        get() = when {
            groups.isEmpty() -> 0
            combinedTitle != null -> 1
            else -> groups.size
        }

    override fun getItemCount(): Int = sectionTabs + customFragments.size

    override fun createFragment(position: Int): Fragment {
        return when {
            position >= sectionTabs -> customFragments[position - sectionTabs].second
            combinedTitle != null -> GroupFragment.newInstance(groups)
            else -> GroupFragment.newInstance(groups[position])
        }
    }

    fun getTabTitle(position: Int): String {
        return when {
            position >= sectionTabs -> customFragments[position - sectionTabs].first
            combinedTitle != null -> combinedTitle
            else -> groups[position].text
        }
    }
}
