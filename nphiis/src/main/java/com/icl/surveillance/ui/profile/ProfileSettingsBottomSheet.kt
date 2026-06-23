package com.icl.surveillance.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.icl.surveillance.R

class ProfileSettingsBottomSheet : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_profile_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.actionResetResourceSync).setOnClickListener {
            parentFragmentManager.setFragmentResult(
                RESULT_KEY,
                bundleOf(RESULT_ACTION to ACTION_RESET_RESOURCE_SYNC)
            )
            dismiss()
        }

        view.findViewById<View>(R.id.btnCancelSheet).setOnClickListener {
            dismiss()
        }
    }

    companion object {
        const val RESULT_KEY = "profile_settings_result"
        const val RESULT_ACTION = "profile_settings_action"
        const val ACTION_RESET_RESOURCE_SYNC = 1

        fun show(fragmentManager: FragmentManager) {
            ProfileSettingsBottomSheet().show(
                fragmentManager,
                ProfileSettingsBottomSheet::class.simpleName
            )
        }
    }
}
