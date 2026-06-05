package com.icl.surveillance.ui.notifications

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.annotation.StringRes
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.icl.surveillance.R
import com.icl.surveillance.adapters.NotificationAdapter
import com.icl.surveillance.adapters.NotificationMonthSection
import com.icl.surveillance.databinding.ActivityNotificationBinding
import com.icl.surveillance.models.Notification
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NotificationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationBinding

    private val viewModel: NotificationViewModel by viewModels()
    private lateinit var adapter: NotificationAdapter
    private var allNotifications: List<Notification> = emptyList()
    private var monthSections: List<NotificationMonthSection> = emptyList()
    private var selectedChipPosition = 0
    private var searchQuery = ""
    private var sortOption = NotificationSortOption.TIME_DESC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityNotificationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarTheme()
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar.apply { title = getString(R.string.notifications_title) }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupRecyclerView()
        observeViewModel()

        viewModel.fetchNotifications(this)
    }

    private fun setupRecyclerView() {
        adapter = NotificationAdapter(this)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is NotificationUiState.Loading -> showLoading()
                    is NotificationUiState.Success -> showData(state.notifications)
                    is NotificationUiState.Empty -> showEmpty()
                    is NotificationUiState.Error -> showError(state.message)
                }
            }
        }
    }

    private fun showLoading() {
        binding.progressBar.visibility = View.VISIBLE
        binding.monthChipScrollView.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
        binding.emptyStateLayout.visibility = View.GONE
    }

    private fun showData(data: List<Notification>) {
        binding.progressBar.visibility = View.GONE
        allNotifications = data
        monthSections = NotificationAdapter.buildMonthSections(this, data)
        selectedChipPosition = 0
        renderMonthChips()
        binding.monthChipScrollView.visibility = View.VISIBLE
        renderVisibleNotifications()
    }

    private fun showEmpty() {
        binding.progressBar.visibility = View.GONE
        allNotifications = emptyList()
        monthSections = emptyList()
        binding.monthChipScrollView.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
        binding.emptyStateLayout.visibility = View.VISIBLE
        binding.tvEmptyTitle.text = getString(R.string.no_notifications_yet)
        binding.tvErrorText.text = getString(R.string.notifications_empty_message)
    }

    private fun showError(message: String) {
        binding.progressBar.visibility = View.GONE
        binding.monthChipScrollView.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
        binding.emptyStateLayout.visibility = View.VISIBLE
        binding.tvErrorText.text = getString(R.string.notifications_error, message)
    }

    private fun renderMonthChips() {
        binding.monthChipGroup.setOnCheckedStateChangeListener(null)
        binding.monthChipGroup.removeAllViews()

        addMonthChip(getString(R.string.notifications_tab_all), 0)
        monthSections.forEachIndexed { index, section ->
            addMonthChip(section.title, index + 1)
        }

        binding.monthChipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val checkedChipId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val selectedChip = group.findViewById<Chip>(checkedChipId) ?: return@setOnCheckedStateChangeListener
            val position = selectedChip.tag as? Int ?: return@setOnCheckedStateChangeListener
            showNotificationsForChip(position)
        }

        val chipCount = binding.monthChipGroup.childCount
        val resolvedPosition = selectedChipPosition.coerceIn(0, (chipCount - 1).coerceAtLeast(0))
        (binding.monthChipGroup.getChildAt(resolvedPosition) as? Chip)?.let { selectedChip ->
            binding.monthChipGroup.check(selectedChip.id)
            binding.monthChipScrollView.post { binding.monthChipScrollView.smoothScrollTo(0, 0) }
        }
    }

    private fun addMonthChip(title: String, position: Int) {
        val chip = Chip(this).apply {
            id = View.generateViewId()
            tag = position
            text = title
            isCheckable = true
            isCheckedIconVisible = false
            isCloseIconVisible = false
            isClickable = true
            chipBackgroundColor = chipBackgroundColors()
            chipStrokeColor = chipStrokeColors()
            chipStrokeWidth = dp(1)
            chipCornerRadius = dp(16)
            setTextColor(chipTextColors())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = ResourcesCompat.getFont(context, R.font.montserratsemi)
            chipStartPadding = dp(10)
            chipEndPadding = dp(10)
            textStartPadding = 0f
            textEndPadding = 0f
            minHeight = dp(32).toInt()
            minimumHeight = dp(32).toInt()
            setEnsureMinTouchTargetSize(false)
            rippleColor = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.notification_tab_ripple)
            )
        }

        binding.monthChipGroup.addView(chip)
    }

    private fun chipBackgroundColors(): ColorStateList {
        return ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(
                ContextCompat.getColor(this, R.color.notification_tab_selected_bg),
                ContextCompat.getColor(this, R.color.account_card_background)
            )
        )
    }

    private fun chipStrokeColors(): ColorStateList {
        return ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(
                ContextCompat.getColor(this, R.color.notification_tab_selected_border),
                ContextCompat.getColor(this, R.color.notification_tab_unselected_border)
            )
        )
    }

    private fun chipTextColors(): ColorStateList {
        return ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(
                ContextCompat.getColor(this, R.color.notification_tab_selected_text),
                ContextCompat.getColor(this, R.color.notification_tab_unselected_text)
            )
        )
    }

    private fun showNotificationsForChip(position: Int) {
        selectedChipPosition = position
        renderVisibleNotifications()
    }

    private fun renderVisibleNotifications() {
        val notifications = getVisibleNotifications()
        if (notifications.isEmpty()) {
            binding.recyclerView.visibility = View.GONE
            binding.emptyStateLayout.visibility = View.VISIBLE
            binding.tvEmptyTitle.text = getString(R.string.notifications_no_results_title)
            binding.tvErrorText.text = when {
                allNotifications.isEmpty() -> getString(R.string.notifications_empty_message)
                searchQuery.isNotBlank() -> getString(R.string.notifications_filtered_empty_message)
                else -> getString(R.string.notifications_empty_message)
            }
            return
        }

        binding.emptyStateLayout.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        adapter.updateData(notifications)
    }

    private fun getVisibleNotifications(): List<Notification> {
        val chipFilteredNotifications = when {
            monthSections.isEmpty() -> emptyList()
            selectedChipPosition == 0 -> monthSections.flatMap { it.notifications }
            else -> monthSections.getOrNull(selectedChipPosition - 1)?.notifications.orEmpty()
        }

        val queryFilteredNotifications = if (searchQuery.isBlank()) {
            chipFilteredNotifications
        } else {
            chipFilteredNotifications.filter { notification ->
                notification.title.contains(searchQuery, ignoreCase = true) ||
                    notification.body.contains(searchQuery, ignoreCase = true)
            }
        }

        return when (sortOption) {
            NotificationSortOption.NAME_ASC ->
                queryFilteredNotifications.sortedBy { it.sortName() }
            NotificationSortOption.NAME_DESC ->
                queryFilteredNotifications.sortedByDescending { it.sortName() }
            NotificationSortOption.TIME_ASC ->
                queryFilteredNotifications.sortedBy { it.primarySortInstant() ?: Instant.MIN }
            NotificationSortOption.TIME_DESC ->
                queryFilteredNotifications.sortedByDescending { it.primarySortInstant() ?: Instant.MIN }
        }
    }

    private fun dp(value: Int): Float {
        return value * resources.displayMetrics.density
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_notifications, menu)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.notifications_search_hint)
        searchView.maxWidth = Int.MAX_VALUE
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                searchQuery = query.orEmpty().trim()
                renderVisibleNotifications()
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText.orEmpty().trim()
                renderVisibleNotifications()
                return true
            }
        })
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                if (searchQuery.isNotBlank()) {
                    searchQuery = ""
                    searchView.setQuery("", false)
                    renderVisibleNotifications()
                }
                return true
            }
        })

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_filter -> {
                showSortDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSortDialog() {
        val options = NotificationSortOption.entries.map { getString(it.labelRes) }.toTypedArray()
        val selectedIndex = NotificationSortOption.entries.indexOf(sortOption)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notifications_sort_title)
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                sortOption = NotificationSortOption.entries[which]
                renderVisibleNotifications()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applySystemBarTheme() {
        val isDarkMode =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES

        window.statusBarColor = ContextCompat.getColor(this, R.color.home_header_start)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.nav_bar_background)

        WindowCompat.getInsetsController(window, window.decorView)?.apply {
            isAppearanceLightStatusBars = !isDarkMode
            isAppearanceLightNavigationBars = !isDarkMode
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private enum class NotificationSortOption(@StringRes val labelRes: Int) {
        NAME_ASC(R.string.notifications_sort_name_asc),
        NAME_DESC(R.string.notifications_sort_name_desc),
        TIME_DESC(R.string.notifications_sort_time_desc),
        TIME_ASC(R.string.notifications_sort_time_asc)
    }

    private fun Notification.sortName(): String {
        return title.ifBlank { body.ifBlank { getString(R.string.notification_default_title) } }
            .lowercase(Locale.getDefault())
    }

    private fun Notification.primarySortInstant(): Instant? {
        return createdAt.toInstantOrNull() ?: updatedAt.toInstantOrNull()
    }

    private fun String.toInstantOrNull(): Instant? {
        if (isBlank()) return null

        return runCatching { Instant.parse(this) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(this).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(this).toInstant() }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(this).atZone(ZoneId.systemDefault()).toInstant()
            }.getOrNull()
    }
}
