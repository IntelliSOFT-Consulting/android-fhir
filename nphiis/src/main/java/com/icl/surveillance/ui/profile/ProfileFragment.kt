package com.icl.surveillance.ui.profile

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.icl.surveillance.R
import com.icl.surveillance.auth.LoginActivity
import com.icl.surveillance.auth.PinLockActivity
import com.icl.surveillance.databinding.FragmentProfileBinding
import com.icl.surveillance.databinding.ItemLabelValueModernBinding
import com.icl.surveillance.fhir.DemoDataStore
import com.icl.surveillance.monitor.DialogHelper
import com.icl.surveillance.models.UserProfilePrefs
import com.icl.surveillance.models.UserRole
import com.icl.surveillance.network.SessionManager
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.utils.NetworkUtils
import java.io.File
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.ResourceType

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val selectedSyncResources = linkedSetOf<ResourceType>()

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    companion object {
        private const val KEY_SELECTED_SYNC_RESOURCES = "selected_sync_resources"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        val root: View = binding.root


        return root
    }

    fun setLabelValue(
        bindingSection: ItemLabelValueModernBinding,
        labelText: String,
        valueText: String
    ) {
        bindingSection.tvLabel.text = labelText
        bindingSection.tvValue.text = valueText
    }

    private fun deleteDir(dir: File?): Boolean {
        if (dir != null && dir.isDirectory) {
            val children = dir.list()
            for (child in children!!) {
                val success = deleteDir(File(dir, child))
                if (!success) return false
            }
            return dir.delete()
        } else if (dir != null && dir.isFile) {
            return dir.delete()
        }
        return false
    }

    private fun restoreSelectedSyncResources(savedInstanceState: Bundle?) {
        val savedResourceNames =
            savedInstanceState?.getStringArrayList(KEY_SELECTED_SYNC_RESOURCES) ?: return
        selectedSyncResources.clear()
        selectedSyncResources.addAll(
            DemoDataStore.resettableResourceTypes.filter { it.name in savedResourceNames }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        restoreSelectedSyncResources(savedInstanceState)
        setupProfileSettingsBottomSheetResult()
        setupProfileMenu()
        binding.apply {

            mapUserData()

            btnSync.setOnClickListener {
                if (NetworkUtils.isInternetAvailable(requireContext())) {
                    startActivity(Intent(requireContext(), SyncUploadActivity::class.java))
                } else {
                    DialogHelper.showSyncRequiresInternetDialog(requireContext())
                }
            }

            btnClearCache.setOnClickListener {
                showConfirmationDialog(
                    title = "Confirmation?",
                    message = "Are you sure you want to clear App Cache?",
                    onConfirm = { clearAppCache() }
                )
            }

            btnClearData.setOnClickListener {
                showConfirmationDialog(
                    title = "Confirmation?",
                    message = "Are you sure you want to clear App Data?",
                    onConfirm = { clearAppData() }
                )
            }

            btnLogout.setOnClickListener {
                showConfirmationDialog(
                    title = "Logout Confirmation?",
                    message = "Are you sure you want to Logout?",
                    onConfirm = { logoutUser() }
                )
            }

            btnChangePassword.setOnClickListener {
                startActivity(
                    Intent(requireContext(), PinLockActivity::class.java).apply {
                        putExtra(PinLockActivity.EXTRA_IS_PROFILE_PASSWORD_CHANGE, true)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mapUserData()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(
            KEY_SELECTED_SYNC_RESOURCES,
            ArrayList(selectedSyncResources.map { it.name })
        )
    }

    fun getUserPrefs(context: Context): UserProfilePrefs {
        val f = FormatterClass()
        println("started loading user profile Ready to return data")
        return UserProfilePrefs(
            f.getSharedPref("firstName", context) ?: "N/A",
            f.getSharedPref("lastName", context) ?: "N/A",
            f.getSharedPref("fullNames", context) ?: "N/A",
            f.getSharedPref("email", context) ?: "",
            f.getSharedPref("phone", context) ?: "-",
            f.getSharedPref("idNumber", context) ?: "",
            f.getSharedPref("role", context) ?: "",
            f.getSharedPref("county", context) ?: "",
            f.getSharedPref("countyName", context) ?: "",
            f.getSharedPref("subCounty", context) ?: "",
            f.getSharedPref("subCountyName", context) ?: "",
            f.getSharedPref("ward", context) ?: "",
            f.getSharedPref("wardName", context) ?: "",
            f.getSharedPref("facility", context) ?: "",
            f.getSharedPref("facilityName", context) ?: ""
        )
    }

    fun safeText(value: String?): String {
        return if (value.isNullOrBlank() || value == "null" || value.equals("NULL", true)) {
            "-"
        } else {
            value
        }
    }

    private fun orderedSelectedSyncResources(): List<ResourceType> {
        return DemoDataStore.resettableResourceTypes.filter { it in selectedSyncResources }
    }

    private fun setupProfileMenu() {
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuInflater.inflate(R.menu.menu_profile, menu)
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                    return when (menuItem.itemId) {
                        R.id.action_profile_settings -> {
                            ProfileSettingsBottomSheet.show(childFragmentManager)
                            true
                        }

                        else -> false
                    }
                }
            },
            viewLifecycleOwner,
            Lifecycle.State.RESUMED
        )
    }

    private fun setupProfileSettingsBottomSheetResult() {
        childFragmentManager.setFragmentResultListener(
            ProfileSettingsBottomSheet.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            when (bundle.getInt(ProfileSettingsBottomSheet.RESULT_ACTION)) {
                ProfileSettingsBottomSheet.ACTION_RESET_RESOURCE_SYNC -> {
                    showSyncResourceSelectionDialog()
                }
            }
        }
    }

    private fun showSyncResourceSelectionDialog() {
        val resettableResources = DemoDataStore.resettableResourceTypes
        val resourceLabels = resettableResources.map { it.toDisplayName() }.toTypedArray()
        val checkedItems = resettableResources.map { it in selectedSyncResources }.toBooleanArray()

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.resource_sync_selection_title)
            .setMultiChoiceItems(resourceLabels, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setNeutralButton(R.string.select_all, null)
            .setPositiveButton(R.string.apply) { dialog, _ ->
                selectedSyncResources.clear()
                resettableResources
                    .filterIndexed { index, _ -> checkedItems[index] }
                    .forEach(selectedSyncResources::add)
                dialog.dismiss()
                confirmSyncResourceReset()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            checkedItems.indices.forEach { index ->
                checkedItems[index] = true
                dialog.listView.setItemChecked(index, true)
            }
        }
    }

    private fun confirmSyncResourceReset() {
        val chosenResources = orderedSelectedSyncResources()
        if (chosenResources.isEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.resource_sync_select_at_least_one),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val selectedResourceNames = chosenResources.joinToString(", ") { it.toDisplayName() }
        showConfirmationDialog(
            title = getString(R.string.reset_resource_sync_title),
            message = getString(R.string.resource_sync_reset_confirmation, selectedResourceNames),
            confirmText = getString(R.string.resource_sync_reset_confirm_action),
            onConfirm = { clearSelectedSyncTimestamps(chosenResources) }
        )
    }

    private fun clearSelectedSyncTimestamps(resourceTypes: List<ResourceType>) {
        lifecycleScope.launch {
            runCatching {
                DemoDataStore(requireContext().applicationContext).clearTimestamps(resourceTypes)
            }.onSuccess {
                Toast.makeText(
                    requireContext(),
                    getString(
                        R.string.resource_sync_reset_success,
                        resourceTypes.joinToString(", ") { it.toDisplayName() }
                    ),
                    Toast.LENGTH_SHORT
                ).show()
                selectedSyncResources.clear()
            }.onFailure {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.resource_sync_reset_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun ResourceType.toDisplayName(): String {
        return name.replace(Regex("([a-z])([A-Z])"), "$1 $2")
    }

    private fun mapUserData() {
        try {
            binding.apply {
                val formatter = FormatterClass()
                val firstName = formatter.getSharedPref("firstName", requireContext())
                val lastName = formatter.getSharedPref("lastName", requireContext())

                val phone = formatter.getSharedPref("phone", requireContext())
                val email = formatter.getSharedPref("email", requireContext())
                val role = formatter.getSharedPref("role", requireContext())
                val fullNames = formatter.getSharedPref("fullNames", requireContext())

                tvUserName.text = "${safeText(firstName)} ${safeText(lastName)}"
                tvEmail.text = " ${safeText(email)}"
                tvPhone.text = " ${safeText(phone)}"
                profileInitials.text = formatter.generateUserAvatarInitials(firstName, lastName, fullNames)


                // Set reusable items
                val user = getUserPrefs(requireContext())
                println("started loading user profile Returned data {$user}")

                setLabelValue(idItem, "ID Number:", user.idNumber)
                setLabelValue(roleItem, "Role:", user.role)
                setLabelValue(
                    countyItem,
                    "County:", user.countyName
                )
                setLabelValue(
                    subCountyItem,
                    "Sub-county:", user.subCountyName
                )
                setLabelValue(
                    wardItem,
                    "Ward:", user.wardName
                )
                setLabelValue(
                    facilityItem,
                    "Facility:", user.facilityName
                )
                val userRole = UserRole.fromAny(user.role)

                when (userRole) {
                    UserRole.ADMINISTRATOR -> {
                        countyItem.lnParent.visibility = View.GONE
                        subCountyItem.lnParent.visibility = View.GONE
                        wardItem.lnParent.visibility = View.GONE
                        facilityItem.lnParent.visibility = View.GONE
                    }

                    UserRole.SUPERUSER -> {
                        countyItem.lnParent.visibility = View.GONE
                        subCountyItem.lnParent.visibility = View.GONE
                        wardItem.lnParent.visibility = View.GONE
                        facilityItem.lnParent.visibility = View.GONE
                    }

                    UserRole.COUNTY_DISEASE_SURVEILLANCE_OFFICER -> {
                        countyItem.lnParent.visibility = View.VISIBLE
                        subCountyItem.lnParent.visibility = View.GONE
                        wardItem.lnParent.visibility = View.GONE
                        facilityItem.lnParent.visibility = View.GONE

                    }

                    UserRole.SUBCOUNTY_DISEASE_SURVEILLANCE_OFFICER -> {
                        countyItem.lnParent.visibility = View.VISIBLE
                        subCountyItem.lnParent.visibility = View.VISIBLE
                        wardItem.lnParent.visibility = View.GONE
                        facilityItem.lnParent.visibility = View.GONE

                    }

                    UserRole.FACILITY_SURVEILLANCE_FOCAL_PERSON,
                    UserRole.SUPERVISOR,
                    UserRole.VACCINATOR -> {

                    }

                    else -> {
                    }
                }

            }

        } catch (e: Exception) {
            e.printStackTrace()
            println("started loading user profile Error ${e.message}")
        }
    }

    private fun showConfirmationDialog(
        title: String,
        message: String,
        confirmText: String = "Yes, Proceed!",
        onConfirm: () -> Unit
    ) {

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(confirmText) { dialog, _ ->
                onConfirm()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()

    }

    private fun clearAppCache() {
        val cacheDir = requireActivity().cacheDir
        if (deleteDir(cacheDir)) {
            Toast.makeText(requireContext(), "Cache cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearAppData() {
        val activityManager =
            requireActivity().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.clearApplicationUserData()
        startActivity(
            Intent(requireContext(), LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        requireActivity().finish()
    }

    private fun logoutUser() {
        val activity = requireActivity()
        val appContext = requireContext().applicationContext

        lifecycleScope.launch {
            SessionManager.clearAuthenticatedSession(appContext)
            activity.startActivity(
                Intent(activity, LoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            activity.finish()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
