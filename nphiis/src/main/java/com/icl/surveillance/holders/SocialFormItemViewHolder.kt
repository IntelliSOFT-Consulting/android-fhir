package com.icl.surveillance.holders

import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.icl.surveillance.R
import com.icl.surveillance.databinding.SocialFormItemViewBinding
import com.icl.surveillance.ui.patients.PatientListViewModel

class SocialFormItemViewHolder(
    binding: SocialFormItemViewBinding
) : RecyclerView.ViewHolder(binding.root) {

    private val badge: TextView = binding.tvBadge
    private val date: TextView = binding.tvDate
    private val title: TextView = binding.tvTitle
    private val subtitle: TextView = binding.tvSubtitle
    private val primaryLabel: TextView = binding.tvPrimaryLabel
    private val primaryValue: TextView = binding.tvPrimaryValue
    private val secondaryLabel: TextView = binding.tvSecondaryLabel
    private val secondaryValue: TextView = binding.tvSecondaryValue
    private val tertiaryLabel: TextView = binding.tvTertiaryLabel
    private val tertiaryValue: TextView = binding.tvTertiaryValue
    private val quaternaryLabel: TextView = binding.tvQuaternaryLabel
    private val quaternaryValue: TextView = binding.tvQuaternaryValue
    private val countyChip: TextView = binding.tvCountyChip
    private val subCountyChip: TextView = binding.tvSubCountyChip

    fun bindTo(
        patientItem: PatientListViewModel.PatientItem,
        onItemClicked: (PatientListViewModel.PatientItem) -> Unit,
    ) {
        val isCommunityForm =
            patientItem.encounterQuestionnaire == "rcce-community-questionnaire"

        badge.text = itemView.context.getString(
            if (isCommunityForm) {
                R.string.social_form_badge_community
            } else {
                R.string.social_form_badge_county
            }
        )
        badge.setBackgroundResource(
            if (isCommunityForm) {
                R.drawable.social_form_badge_community_background
            } else {
                R.drawable.social_form_badge_county_background
            }
        )
        badge.setTextColor(
            itemView.context.getColor(
                if (isCommunityForm) {
                    R.color.selection_sheet_secondary_icon_tint
                } else {
                    R.color.selection_sheet_primary_icon_tint
                }
            )
        )

        date.text = resolveDate(patientItem)
        title.text = resolveTitle(patientItem, isCommunityForm)
        subtitle.text = resolveSubtitle(patientItem, isCommunityForm)

        if (isCommunityForm) {
            primaryLabel.setText(R.string.social_form_label_occupation)
            primaryValue.text = formatValue(patientItem.occupation)
            secondaryLabel.setText(R.string.social_form_label_village)
            secondaryValue.text = formatValue(patientItem.village)
            tertiaryLabel.setText(R.string.social_form_label_sex)
            tertiaryValue.text = formatValue(patientItem.gender)
            quaternaryLabel.setText(R.string.social_form_label_age)
            quaternaryValue.text = formatValue(patientItem.respondentAge)
        } else {
            primaryLabel.setText(R.string.social_form_label_ward)
            primaryValue.text = formatValue(patientItem.ward)
            secondaryLabel.setText(R.string.social_form_label_reporting_site)
            secondaryValue.text = formatValue(patientItem.reportingSite)
            tertiaryLabel.setText(R.string.social_form_label_facility_type)
            tertiaryValue.text = formatValue(patientItem.facilityType)
            quaternaryLabel.setText(R.string.social_form_label_sub_county)
            quaternaryValue.text = formatValue(patientItem.subCounty)
        }

        countyChip.text = formatChipValue(
            R.string.social_form_chip_county,
            patientItem.county
        )
        subCountyChip.text = formatChipValue(
            R.string.social_form_chip_sub_county,
            patientItem.subCounty
        )

        itemView.setOnClickListener { onItemClicked(patientItem) }
    }

    private fun resolveTitle(
        patientItem: PatientListViewModel.PatientItem,
        isCommunityForm: Boolean,
    ): String {
        if (isCommunityForm) {
            return patientItem.name.trim()
                .ifBlank { patientItem.village.trim() }
                .ifBlank { itemView.context.getString(R.string.social_form_title_community_default) }
        }

        return patientItem.reportingSite.trim()
            .ifBlank { patientItem.ward.trim() }
            .ifBlank { itemView.context.getString(R.string.social_form_title_county_default) }
    }

    private fun resolveSubtitle(
        patientItem: PatientListViewModel.PatientItem,
        isCommunityForm: Boolean,
    ): String {
        val parts = if (isCommunityForm) {
            listOf(
                patientItem.reportingSite.trim(),
                patientItem.ward.trim(),
                patientItem.subCounty.trim()
            )
        } else {
            listOf(
                patientItem.county.trim(),
                patientItem.subCounty.trim(),
                patientItem.facilityType.trim()
            )
        }.filter { it.isNotBlank() }
            .take(2)

        return parts.joinToString(" • ")
            .ifBlank { itemView.context.getString(R.string.social_form_not_captured) }
    }

    private fun resolveDate(patientItem: PatientListViewModel.PatientItem): String {
        return patientItem.caseOnsetDate.trim()
            .ifBlank { patientItem.lastUpdated.trim() }
            .ifBlank { itemView.context.getString(R.string.social_form_not_captured) }
    }

    private fun formatValue(value: String): String {
        return value.trim().ifBlank {
            itemView.context.getString(R.string.social_form_not_captured)
        }
    }

    private fun formatChipValue(labelRes: Int, value: String): String {
        val resolved = value.trim().ifBlank {
            itemView.context.getString(R.string.social_form_not_captured)
        }
        return itemView.context.getString(labelRes, resolved)
    }
}
