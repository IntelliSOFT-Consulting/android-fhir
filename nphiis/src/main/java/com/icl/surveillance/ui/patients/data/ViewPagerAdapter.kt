package com.icl.surveillance.ui.patients.data

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class ViewPagerAdapter(fragmentActivity: FragmentActivity) :
    FragmentStateAdapter(fragmentActivity) {
  override fun getItemCount(): Int = 7

  override fun createFragment(position: Int): Fragment {
    return when (position) {
      0 -> ReportingSiteFragment()
      1 -> IdentificationFragment()
      3 -> CaseInformationFragment()
      2 -> ClinicalInformationFragment()
      4 -> LabInformationFragment()
      5 -> LabResultsFragment()
      6 -> RegionalLabResultsFragment()
      else -> ReportingSiteFragment()
    }
  }
}
