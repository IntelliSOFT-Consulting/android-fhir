package com.icl.surveillance.ui.notifications

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.icl.surveillance.R
import com.icl.surveillance.adapters.NotificationAdapter
import com.icl.surveillance.databinding.ActivityNotificationBinding
import com.icl.surveillance.models.Notification
import com.icl.surveillance.network.RetrofitCallsAuthentication
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NotificationActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateLayout: View

    private var retrofit = RetrofitCallsAuthentication()
    private lateinit var binding: ActivityNotificationBinding


    private val viewModel: NotificationViewModel by viewModels()
    private lateinit var adapter: NotificationAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityNotificationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar.apply { title = "Notifications" }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

//        adapter = NotificationAdapter(emptyList())
//        binding.notificationRecyclerView.layoutManager = LinearLayoutManager(this)
//        binding.notificationRecyclerView.adapter = adapter

        setupRecyclerView()
        observeViewModel()

        viewModel.fetchNotifications(this)

//        recyclerView = binding.notificationRecyclerView//findViewById(R.id.notificationRecyclerView)
//        emptyStateLayout = binding.emptyStateLayout// findViewById(R.id.emptyStateLayout)
//
//        val notifications = loadNotifications()
//
//        if (notifications.isEmpty()) {
//            showEmptyState()
//        } else {
//            showNotifications(notifications)
//        }
    }

    private fun setupRecyclerView() {
        adapter = NotificationAdapter(emptyList())
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
        binding.recyclerView.visibility = View.GONE
        binding.emptyStateLayout.visibility = View.GONE
    }

    private fun showData(data: List<Notification>) {
        binding.progressBar.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        binding.emptyStateLayout.visibility = View.GONE
        adapter.updateData(data)
    }

    private fun showEmpty() {
        binding.progressBar.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
        binding.emptyStateLayout.visibility = View.VISIBLE
        binding.tvErrorText.text = "No notifications available."
    }

    private fun showError(message: String) {
        binding.progressBar.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
        binding.emptyStateLayout.visibility = View.VISIBLE
        binding.tvErrorText.text = "Error: $message"
    }
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }

    private fun loadNotifications(): List<Notification> {

        retrofit.pullUserAlerts(this)
        // Simulate data loading
        return listOf(
        ) // ← Change to actual data source
    }

    private fun showEmptyState() {
        recyclerView.visibility = View.GONE
        emptyStateLayout.visibility = View.VISIBLE
    }

    private fun showNotifications(notifications: List<Notification>) {
        recyclerView.visibility = View.VISIBLE
        emptyStateLayout.visibility = View.GONE

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = NotificationAdapter(notifications)
    }
}