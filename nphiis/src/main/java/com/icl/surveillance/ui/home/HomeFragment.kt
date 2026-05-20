package com.icl.surveillance.ui.home

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.icl.surveillance.R
import com.icl.surveillance.adapters.HomeRecyclerViewAdapter
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FILE_PATH_KEY
import com.icl.surveillance.databinding.FragmentHomeBinding
import com.icl.surveillance.ui.notifications.NotificationActivity
import com.icl.surveillance.utils.FormatterClass

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val viewModel: HomeViewModel by viewModels()
    private var userPrefs: SharedPreferences? = null

    private val trackedProfileKeys =
        setOf("firstName", "lastName", "fullNames", "id", "role", "practitionerRole")

    private val userPrefChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in trackedProfileKeys) {
                handleUser()
            }
        }

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding
        get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.apply {
            notificationIcon.setOnClickListener {
                startActivity(Intent(requireContext(), NotificationActivity::class.java))
            }
        }

        val adapter =
            HomeRecyclerViewAdapter(
                ::onItemClick,
                true
            ).apply { submitList(viewModel.getLayoutList()) }
        val recyclerView = requireView().findViewById<RecyclerView>(R.id.sdcLayoutsRecyclerView)
        recyclerView.adapter = adapter
        recyclerView.layoutManager = GridLayoutManager(requireContext(), requireContext().homeGridSpanCount())

        registerUserPrefListener()
        handleUser()

    }

    override fun onResume() {
        super.onResume()
        try {
            handleUser()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun safeText(value: String?): String {
        return if (value.isNullOrBlank() || value == "null" || value.equals("NULL", true)) {
            "-"
        } else {
            value
        }
    }

    private fun registerUserPrefListener() {
        val prefs =
            requireContext().getSharedPreferences(
                getString(R.string.app_name),
                android.content.Context.MODE_PRIVATE
            )
        userPrefs?.unregisterOnSharedPreferenceChangeListener(userPrefChangeListener)
        prefs.registerOnSharedPreferenceChangeListener(userPrefChangeListener)
        userPrefs = prefs
    }

    private fun hasMeaningfulValue(value: String?): Boolean {
        return !value.isNullOrBlank() && !value.equals("null", ignoreCase = true)
    }

    private fun isProfileLoaded(formatter: FormatterClass): Boolean {
        return hasMeaningfulValue(formatter.getSharedPref("id", requireContext())) ||
            hasMeaningfulValue(formatter.getSharedPref("role", requireContext())) ||
            hasMeaningfulValue(formatter.getSharedPref("practitionerRole", requireContext()))
    }

    private fun handleUser() {
        val formatter = FormatterClass()
        val firstName = formatter.getSharedPref("firstName", requireContext())
        val lastName = formatter.getSharedPref("lastName", requireContext())
        val fullName = formatter.getSharedPref("fullNames", requireContext())
        val name = getUserNameFromDetails(fullName, firstName, lastName)
        val time = formatter.getTimeOfDay()
        val isLoadingProfile = !isProfileLoaded(formatter) && name.isBlank()

        binding.apply {
            greetingText.text = time
            usernameText.text = if (isLoadingProfile) "\u00A0" else safeText(name)
            usernameLoadingIndicator.visibility = if (isLoadingProfile) View.VISIBLE else View.GONE
        }
    }

    private fun getUserNameFromDetails(fullName: String?, firstName: String?, lastName: String?): String {
        if (!fullName.isNullOrBlank() && !fullName.equals("null", ignoreCase = true)) {
            return fullName
        }
        return listOfNotNull(firstName, lastName)
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            .joinToString(" ")
    }

    private fun onItemClick(layout: HomeViewModel.Layout) {
        val title = context?.getString(layout.textId) ?: ""
        when (layout.count) {
            0 -> {
                handleClick("0", title)
            }

            1 -> {
                handleClick("1", title)
            }

            2 -> {
                handleClick("2", title)
            }

            3 -> {
                handleClick("3", title)
            }

            4 -> {
               showComingSoon()
            }

            else -> {
                showComingSoon()
            }


        }

    }
fun showComingSoon(){
   
                FormatterClass().showComingSoon(requireContext())
}
    private fun handleClick(stage: String, title: String) {
        val bundle =
            Bundle().apply { putString(QUESTIONNAIRE_FILE_PATH_KEY, "add-vl.json") }

        FormatterClass()
            .saveSharedPref(
                "stage", stage,
                requireContext()
            )
        FormatterClass()
            .saveSharedPref(
                "title", title,
                requireContext()
            )

        navigateIfActionAvailable(
            expectedDestinationId = R.id.navigation_home,
            actionId = R.id.action_navigation_home_to_childFragment,
            args = bundle,
        )
    }

    override fun onDestroyView() {
        userPrefs?.unregisterOnSharedPreferenceChangeListener(userPrefChangeListener)
        userPrefs = null
        super.onDestroyView()
        _binding = null
    }
}
