package com.icl.surveillance.ui.patients

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.icl.surveillance.R
import com.icl.surveillance.ui.patients.data.ViewPagerAdapter

class CaseDetailsActivity : AppCompatActivity() {

  private lateinit var tabLayout: TabLayout
  private lateinit var viewPager: ViewPager2

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContentView(R.layout.activity_case_details)

    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    tabLayout = findViewById(R.id.tabLayout)
    viewPager = findViewById(R.id.viewPager)

    val adapter = ViewPagerAdapter(this)
    viewPager.adapter = adapter

    TabLayoutMediator(tabLayout, viewPager) { tab, position ->
          tab.text =
              when (position) {
                0 -> "Lab Information"
                1 -> "KEMRI Lab "
                2 -> "Specimen "
                3 -> "Regional "
                else -> "Lab Information"
              }
        }
        .attach()
  }

  override fun onSupportNavigateUp(): Boolean {
    onBackPressedDispatcher.onBackPressed()
    return true
  }
}
