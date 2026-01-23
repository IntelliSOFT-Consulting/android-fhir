package com.icl.surveillance.monitor

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.fhir.FhirEngineProvider
import com.google.android.material.snackbar.Snackbar
import com.icl.surveillance.R
import com.icl.surveillance.databinding.FragmentResourceListBinding
import com.icl.surveillance.utils.NetworkUtils
import kotlinx.coroutines.launch

// TODO: Rename parameter arguments, choose names that match
// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

/**
 * A simple [Fragment] subclass.
 * Use the [ResourceListFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class ResourceListFragment : Fragment() {

    private lateinit var binding: FragmentResourceListBinding
    private lateinit var viewModel: PaginatedViewModel
    private lateinit var adapter: ResourceAdapter
    private lateinit var fhirBundleService: FhirBundleService


    // onCreateView is where you inflate the layout
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout using ViewBinding
        binding = FragmentResourceListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val fhirEngine = FhirEngineProvider.getInstance(requireContext())
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

        bottomSheet.show(parentFragmentManager, "UploadOptionsBottomSheet")
    }

    private fun checkInternetAndUploadAllCurrentType() {
        val currentType = binding.spinner.selectedItem as String
        lifecycleScope.launch {
            val bundleSource = fhirBundleService.createUploadBundle(currentType)
            if (!bundleSource.hasEntry()) {
                showSnackbar("No $currentType resources to upload")
                return@launch
            }
            if (NetworkUtils.isInternetAvailable(requireContext())) {
                viewModel.uploadBundle(bundleSource)

                showSnackbar("Started uploading $currentType resources as bundle")
            } else {
                DialogHelper.showNoInternetDialog(
                    context = requireContext(),
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
//                if (NetworkUtils.isInternetAvailable(requireContext())) {
//                    viewModel.uploadAllResources()
//                    showSnackbar("Started uploading $total total resources as bundle")
//                } else {
//                    DialogHelper.showNoInternetDialog(
//                        context = requireContext(),
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
        if (NetworkUtils.isInternetAvailable(requireContext())) {
            viewModel.uploadSingleResource(resourceId)
        } else {
            DialogHelper.showNoInternetDialog(
                context = requireContext(),
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
            ContextCompat.getColor(requireContext(), R.color.snackbar_error)
        } else {
            ContextCompat.getColor(requireContext(), R.color.snackbar_success)
        }

        // Set text color for better contrast
        val textColor = ContextCompat.getColor(requireContext(), R.color.snackbar_text)

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
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())

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
            arrayOf("Patient", "QuestionnaireResponse", "MeasureReport", "Encounter", "Observation")

        // Create custom adapter with forced black text
        val adapter = object : ArrayAdapter<String>(
            requireContext(),
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
                    ContextCompat.getColor(requireContext(), R.color.black)
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
                    ContextCompat.getColor(requireContext(), R.color.black)
                )
            }
            loadMoreButton.setOnClickListener {
                viewModel.loadNextPage()
            }
        }
    }


    private fun showNoMoreItems() {
        Toast.makeText(requireContext(), "All items loaded", Toast.LENGTH_SHORT).show()
    }
}