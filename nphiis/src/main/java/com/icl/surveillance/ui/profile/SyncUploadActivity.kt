package com.icl.surveillance.ui.profile

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.fhir.FhirEngineProvider
import com.google.android.material.snackbar.Snackbar
import com.icl.surveillance.R
import com.icl.surveillance.databinding.ActivitySyncUploadBinding
import com.icl.surveillance.monitor.DialogHelper
import com.icl.surveillance.monitor.FhirBundleService
import com.icl.surveillance.monitor.PaginatedViewModel
import com.icl.surveillance.monitor.ResourceAdapter
import com.icl.surveillance.monitor.UploadOptionsBottomSheet
import com.icl.surveillance.utils.NetworkUtils
import kotlinx.coroutines.launch

class SyncUploadActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySyncUploadBinding
    private lateinit var viewModel: PaginatedViewModel
    private lateinit var adapter: ResourceAdapter
    private lateinit var fhirBundleService: FhirBundleService
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivitySyncUploadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar.apply { title = "Resource Sync" }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }


        val fhirEngine = FhirEngineProvider.getInstance(this@SyncUploadActivity)
        viewModel = PaginatedViewModel(fhirEngine)
        fhirBundleService = FhirBundleService(fhirEngine)


        setupRecyclerView()
        setupObservers()
        setupUI()

        // Load first page
        viewModel.loadFirstPage("Patient")
        binding.apply {
            fabUploadOptions.setOnClickListener {
                showUploadOptionsBottomSheet()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }


    private fun showUploadOptionsBottomSheet() {
        val currentType = binding.spinner.selectedItem as String

        val bottomSheet = UploadOptionsBottomSheet.newInstance(
            currentResourceType = currentType,
            onUploadCurrentType = {
                checkInternetAndUploadAllCurrentType()
            },
            onUploadAllTypes = {
                checkInternetAndUploadAllResources()
            }
        )

        bottomSheet.show(supportFragmentManager, "UploadOptionsBottomSheet")
    }

    private fun checkInternetAndUploadAllCurrentType() {
        val currentType = binding.spinner.selectedItem as String
        lifecycleScope.launch {
            val bundleSource = fhirBundleService.createUploadBundle(currentType)
            if (!bundleSource.hasEntry()) {
                showSnackbar("No $currentType resources to upload")
                return@launch
            }
            if (NetworkUtils.isInternetAvailable(this@SyncUploadActivity)) {
                viewModel.uploadBundle(bundleSource)

                showSnackbar("Started uploading $currentType resources as bundle")
            } else {
                DialogHelper.showNoInternetDialog(
                    context = this@SyncUploadActivity,
                    onRetry = {
                        viewModel.uploadBundle(bundleSource)
                    }
                )
            }

        }
    }


    private fun checkInternetAndUploadAllResources() {
        lifecycleScope.launch {
//            val counts = viewModel.getPendingResourceCounts()
//            val total = counts.values.sum()
//            if (total > 0) {
//                if (NetworkUtils.isInternetAvailable(this@SyncUploadActivity)) {
//                    viewModel.uploadAllResources()
//                    showSnackbar("Started uploading $total total resources as bundle")
//                } else {
//                    DialogHelper.showNoInternetDialog(
//                        context = this@SyncUploadActivity,
//                        onRetry = {
//                            viewModel.uploadAllResources()
//                        }
//                    )
//                }
//            } else {
//                showSnackbar("No resources to upload")
//            }
        }
    }

    private fun checkInternetAndUpload(resourceId: String, isRetry: Boolean) {
        if (NetworkUtils.isInternetAvailable(this@SyncUploadActivity)) {
            viewModel.uploadSingleResource(resourceId)
        } else {
            DialogHelper.showNoInternetDialog(
                context = this@SyncUploadActivity,
                onRetry = {
                    if (isRetry) {
                        viewModel.retryUpload(resourceId)
                    } else {
                        viewModel.uploadSingleResource(resourceId)
                    }
                },
                onCancel = {
                    // User cancelled due to no internet
                    showSnackbar("Upload cancelled - No internet connection")
                }
            )
        }
    }

    private fun showSnackbar(message: String, isError: Boolean = false) {
        val snackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)

        // Set background color based on success/error
        val backgroundColor = if (isError) {
            ContextCompat.getColor(this@SyncUploadActivity, R.color.snackbar_error)
        } else {
            ContextCompat.getColor(this@SyncUploadActivity, R.color.snackbar_success)
        }

        // Set text color for better contrast
        val textColor = ContextCompat.getColor(this@SyncUploadActivity, R.color.snackbar_text)

        snackbar.view.setBackgroundColor(backgroundColor)
        snackbar.setTextColor(textColor)

        // Optional: Add an icon
        val iconRes =
            if (isError) R.drawable.baseline_error_24 else R.drawable.baseline_check_circle_24
        snackbar.setAction("") { /* Empty action for icon */ }
//        snackbar.ic(iconRes,null)

        snackbar.show()
    }

    private fun setupRecyclerView() {

        adapter = ResourceAdapter(onUploadClick = { resourceId ->

            checkInternetAndUpload(resourceId, false)
        }, onRetryClick = { resourceId ->

            checkInternetAndUpload(resourceId, true)
        })
        binding.recyclerView.adapter = adapter
        binding.recyclerView.layoutManager = LinearLayoutManager(this@SyncUploadActivity)

        // Add scroll listener for pagination
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val visibleItemCount = layoutManager.childCount
                val totalItemCount = layoutManager.itemCount
                val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                // Load more when we're near the end
                if (!viewModel.isLoading.value &&
                    viewModel.hasMore.value &&
                    (visibleItemCount + firstVisibleItemPosition) >= totalItemCount - 5
                ) {
                    viewModel.loadNextPage()
                }
            }
        })
    }

    private fun updateLoadMoreButtonVisibility() {
        val hasResources = !viewModel.resources.value.isEmpty()
        val hasMore = viewModel.hasMore.value
        val isLoading = viewModel.isLoading.value

        // Show load more button only when:
        // - There are resources displayed
        // - There are more resources to load
        // - Not currently loading
        if (hasResources && hasMore && !isLoading) {
            binding.loadMoreButton.visibility = View.VISIBLE
        } else {
            binding.loadMoreButton.visibility = View.GONE
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty) {
            // Show empty state, hide list and load more button
            binding.emptyState.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            binding.loadMoreButton.visibility = View.GONE
        } else {
            // Show list, hide empty state
            binding.emptyState.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            updateLoadMoreButtonVisibility()
        }
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.resources.collect { resources ->
                adapter.submitList(resources)
                updateEmptyState(resources.isEmpty())
                updateLoadMoreButtonVisibility()
            }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            viewModel.hasMore.collect { hasMore ->
                // Show/hide load more button or indicator
                if (!hasMore && viewModel.resources.value.isNotEmpty()) {
                    showNoMoreItems()
                }
            }
        }
    }

    private fun setupUI() {
        val resourceTypes =
            arrayOf(
                "Patient",
                "Encounter",
                "QuestionnaireResponse",
                "MeasureReport",
                "Observation"
            )

        // Create custom adapter with forced black text
        val adapter = object : ArrayAdapter<String>(
            this@SyncUploadActivity,
            R.layout.spinner_item,
            resourceTypes
        ) {
            override fun getDropDownView(
                position: Int,
                convertView: View?,
                parent: ViewGroup
            ): View {
                val view = super.getDropDownView(position, convertView, parent)
                // Force black text for dropdown items
                (view as TextView).setTextColor(ContextCompat.getColor(context, R.color.black))
                return view
            }
        }

        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        binding.spinner.adapter = adapter

        binding.spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                val selectedType = resourceTypes[position]
                viewModel.loadFirstPage(selectedType)

                // Force black text on selected item
                (parent.getChildAt(0) as? TextView)?.setTextColor(
                    ContextCompat.getColor(this@SyncUploadActivity, R.color.black)
                )
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        binding.apply {

            // Set initial selection
            spinner.setSelection(0)

            // Force initial text color
            spinner.post {
                (binding.spinner.selectedView as? TextView)?.setTextColor(
                    ContextCompat.getColor(this@SyncUploadActivity, R.color.black)
                )
            }
            loadMoreButton.setOnClickListener {
                viewModel.loadNextPage()
            }
        }
    }


    private fun showNoMoreItems() {
        Toast.makeText(this@SyncUploadActivity, "All items loaded", Toast.LENGTH_SHORT).show()
    }
}