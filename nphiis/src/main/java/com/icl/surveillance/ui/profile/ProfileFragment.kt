package com.icl.surveillance.ui.profile

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import cn.pedant.SweetAlert.SweetAlertDialog
import com.icl.surveillance.auth.LoginActivity
import com.icl.surveillance.databinding.FragmentProfileBinding
import com.icl.surveillance.databinding.ItemLabelValueModernBinding
import com.icl.surveillance.models.UserProfilePrefs
import com.icl.surveillance.models.UserRole
import com.icl.surveillance.utils.FormatterClass
import java.io.File

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.apply {

            mapUserData()

            btnSync.setOnClickListener {
                startActivity(Intent(requireContext(), SyncUploadActivity::class.java))
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

        }
    }

    override fun onResume() {
        super.onResume()
        mapUserData()
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
    private fun mapUserData() {
        try {
            binding.apply {
                val formatter = FormatterClass()
                val firstName = formatter.getSharedPref("firstName", requireContext())
                val lastName = formatter.getSharedPref("lastName", requireContext())

                val phone = formatter.getSharedPref("phone", requireContext())
                val email = formatter.getSharedPref("email", requireContext())
                val role = formatter.getSharedPref("role", requireContext())

                tvUserName.text = "${safeText(firstName)} ${safeText(lastName)}"
                tvEmail.text = " ${safeText(email)}"
                tvPhone.text = " ${safeText(phone)}"


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
        SweetAlertDialog(requireContext(), SweetAlertDialog.WARNING_TYPE).apply {
            setTitleText(title)
            setContentText(message)
            setConfirmText(confirmText)
            setConfirmClickListener { sDialog ->
                onConfirm()
                sDialog.dismissWithAnimation()
            }
            show()
        }
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
        FormatterClass().clearCache(requireContext())
        FormatterClass().deleteSharedPref("isLoggedIn", requireContext())
        startActivity(
            Intent(requireContext(), LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        requireActivity().finish()
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}