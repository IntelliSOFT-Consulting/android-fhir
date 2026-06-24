package com.icl.surveillance.ui.home.sheet


import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.widget.ImageViewCompat
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
        val countyTitle =
            arguments?.getString(ARG_COUNTY_TITLE)?.takeUnless { it.isNullOrBlank() }
                ?: getString(R.string.county_sub_county)
        val communityTitle =
            arguments?.getString(ARG_COMMUNITY_TITLE)?.takeUnless { it.isNullOrBlank() }
                ?: getString(R.string.community_tool)

        bindOption(
            root = view,
            cardId = R.id.card_county,
            iconContainerId = R.id.fl_county_icon_container,
            iconId = R.id.iv_county_icon,
            titleId = R.id.tv_county_title,
            subtitleId = R.id.tv_county_subtitle,
            title = countyTitle,
            metadata = metadataFor(countyTitle, CHOICE_COUNTY),
            choice = CHOICE_COUNTY
        )

        bindOption(
            root = view,
            cardId = R.id.card_community,
            iconContainerId = R.id.fl_community_icon_container,
            iconId = R.id.iv_community_icon,
            titleId = R.id.tv_community_title,
            subtitleId = R.id.tv_community_subtitle,
            title = communityTitle,
            metadata = metadataFor(communityTitle, CHOICE_COMMUNITY),
            choice = CHOICE_COMMUNITY
        )
    }

    private fun bindOption(
        root: View,
        cardId: Int,
        iconContainerId: Int,
        iconId: Int,
        titleId: Int,
        subtitleId: Int,
        title: String,
        metadata: OptionMetadata,
        choice: Int,
    ) {
        root.findViewById<TextView>(titleId).text = title
        root.findViewById<TextView>(subtitleId).setText(metadata.subtitleRes)
        root.findViewById<View>(iconContainerId).background =
            ContextCompat.getDrawable(requireContext(), metadata.iconBackgroundRes)

        root.findViewById<ImageView>(iconId).apply {
            setImageResource(metadata.iconRes)
            ImageViewCompat.setImageTintList(
                this,
                ContextCompat.getColorStateList(requireContext(), metadata.iconTintRes)
            )
            contentDescription = title
        }

        root.findViewById<View>(cardId).apply {
            setOnClickListener { sendResult(choice) }
        }
    }

    private fun metadataFor(title: String, choice: Int): OptionMetadata {
        val normalizedTitle = title.lowercase()
        return when {
            normalizedTitle.contains("tally") -> {
                OptionMetadata(
                    iconRes = R.drawable.baseline_check_circle_24,
                    subtitleRes = R.string.selection_sheet_subtitle_tally_sheet,
                    iconTintRes = R.color.selection_sheet_primary_icon_tint,
                    iconBackgroundRes = R.drawable.selection_sheet_icon_primary_background
                )
            }

            normalizedTitle.contains("supervisor") -> {
                OptionMetadata(
                    iconRes = R.drawable.baseline_account_circle_24,
                    subtitleRes = R.string.selection_sheet_subtitle_supervisor_checklist,
                    iconTintRes = R.color.selection_sheet_secondary_icon_tint,
                    iconBackgroundRes = R.drawable.selection_sheet_icon_secondary_background
                )
            }

            normalizedTitle.contains("community") -> {
                OptionMetadata(
                    iconRes = R.drawable.baseline_person_24,
                    subtitleRes = R.string.selection_sheet_subtitle_community_questionnaire,
                    iconTintRes = R.color.selection_sheet_secondary_icon_tint,
                    iconBackgroundRes = R.drawable.selection_sheet_icon_secondary_background
                )
            }

            normalizedTitle.contains("county") || normalizedTitle.contains("sub county") ||
                    normalizedTitle.contains("subcounty") -> {
                OptionMetadata(
                    iconRes = R.drawable.baseline_add_location_24,
                    subtitleRes = R.string.selection_sheet_subtitle_county_assessment,
                    iconTintRes = R.color.selection_sheet_primary_icon_tint,
                    iconBackgroundRes = R.drawable.selection_sheet_icon_primary_background
                )
            }

            choice == CHOICE_COUNTY -> {
                OptionMetadata(
                    iconRes = R.drawable.baseline_add_location_24,
                    subtitleRes = R.string.selection_sheet_subtitle_generic,
                    iconTintRes = R.color.selection_sheet_primary_icon_tint,
                    iconBackgroundRes = R.drawable.selection_sheet_icon_primary_background
                )
            }

            else -> {
                OptionMetadata(
                    iconRes = R.drawable.baseline_person_24,
                    subtitleRes = R.string.selection_sheet_subtitle_generic,
                    iconTintRes = R.color.selection_sheet_secondary_icon_tint,
                    iconBackgroundRes = R.drawable.selection_sheet_icon_secondary_background
                )
            }
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
        private const val ARG_COUNTY_TITLE = "county"
        private const val ARG_COMMUNITY_TITLE = "community"
        const val CHOICE_COUNTY = 1
        const val CHOICE_COMMUNITY = 0


        fun newInstance(county: String, community: String): SelectionBottomSheet {
            val fragment = SelectionBottomSheet()
            fragment.arguments = Bundle().apply {
                putString(ARG_COUNTY_TITLE, county)
                putString(ARG_COMMUNITY_TITLE, community)
            }
            return fragment
        }


        fun show(fm: FragmentManager) =
            SelectionBottomSheet().show(fm, SelectionBottomSheet::class.simpleName)
    }

    private data class OptionMetadata(
        val iconRes: Int,
        val subtitleRes: Int,
        val iconTintRes: Int,
        val iconBackgroundRes: Int,
    )
}
