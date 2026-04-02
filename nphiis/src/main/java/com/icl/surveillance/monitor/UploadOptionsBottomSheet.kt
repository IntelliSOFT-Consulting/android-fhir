package com.icl.surveillance.monitor


import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.icl.surveillance.R
import com.icl.surveillance.utils.NetworkUtils

class UploadOptionsBottomSheet : BottomSheetDialogFragment() {

    private var currentResourceType: String = "Patient"
    private var onUploadCurrentType: (() -> Unit)? = null
    private var onUploadAllTypes: (() -> Unit)? = null

    companion object {
        private const val ARG_RESOURCE_TYPE = "resource_type"

        fun newInstance(
            currentResourceType: String,
            onUploadCurrentType: () -> Unit,
            onUploadAllTypes: () -> Unit
        ): UploadOptionsBottomSheet {
            return UploadOptionsBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_RESOURCE_TYPE, currentResourceType)
                }
                this.onUploadCurrentType = onUploadCurrentType
                this.onUploadAllTypes = onUploadAllTypes
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_upload_options, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        currentResourceType = arguments?.getString(ARG_RESOURCE_TYPE) ?: "Patient"

        setupViews(view)
    }

    private fun setupViews(view: View) {
        // Update current type text
        val tvCurrentTypeTitle = view.findViewById<TextView>(R.id.tvCurrentTypeTitle)
        val tvCurrentTypeDesc = view.findViewById<TextView>(R.id.tvCurrentTypeDesc)

        tvCurrentTypeTitle.text = "Upload All $currentResourceType"
        tvCurrentTypeDesc.text = "Upload all $currentResourceType resources as a single efficient bundle"

        // Upload Current Type Button
        view.findViewById<MaterialButton>(R.id.btnUploadCurrentType).setOnClickListener {
            if (NetworkUtils.isInternetAvailable(requireContext())) {
                onUploadCurrentType?.invoke()
                dismiss()
            } else {
                DialogHelper.showNoInternetDialog(
                    context = requireContext(),
                    onRetry = {
                        onUploadCurrentType?.invoke()
                        dismiss()
                    }
                )
            }
        }

        // Upload All Types Button
        view.findViewById<MaterialButton>(R.id.btnUploadAllTypes).setOnClickListener {
            if (NetworkUtils.isInternetAvailable(requireContext())) {
                onUploadAllTypes?.invoke()
                dismiss()
            } else {
                DialogHelper.showNoInternetDialog(
                    context = requireContext(),
                    onRetry = {
                        onUploadAllTypes?.invoke()
                        dismiss()
                    }
                )
            }
        }

        // Close Button
        view.findViewById<MaterialButton>(R.id.btnCloseSheet).setOnClickListener {
            dismiss()
        }
    }
}