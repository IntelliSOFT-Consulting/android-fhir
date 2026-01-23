package com.icl.surveillance.ui.home.sheet


import android.os.Bundle
import android.view.*
import android.widget.Button
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.icl.surveillance.R

class SelectionBottomSheet : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.layout_bottom_sheet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {


        val county = arguments?.getString("county") ?: ""
        val community = arguments?.getString("community") ?: ""

        if (county.isNotEmpty()) {
            view.findViewById<Button>(R.id.btn_county)?.text = county
        }
        if (community.isNotEmpty()) {
            view.findViewById<Button>(R.id.btn_community)?.text = community
        }

        view.findViewById<Button>(R.id.btn_county).setOnClickListener {
            sendResult(CHOICE_COUNTY)
            dismiss()
        }
        view.findViewById<Button>(R.id.btn_community).setOnClickListener {
            sendResult(CHOICE_COMMUNITY)
            dismiss()
        }
    }


    private fun sendResult(choice: Int) {
        parentFragmentManager.setFragmentResult(
            RESULT_KEY,
            bundleOf(ARG_CHOICE to choice)
        )
        dismiss()
    }

    companion object {

        const val RESULT_KEY = "selection_result"
        const val ARG_CHOICE = "choice"
        const val CHOICE_COUNTY = 1
        const val CHOICE_COMMUNITY = 0


        fun newInstance(county: String, community: String): SelectionBottomSheet {
            val fragment = SelectionBottomSheet()
            fragment.arguments = Bundle().apply {
                putString("county", county)
                putString("community", community)
            }
            return fragment
        }


        fun show(fm: FragmentManager) =
            SelectionBottomSheet().show(fm, SelectionBottomSheet::class.simpleName)
    }
}