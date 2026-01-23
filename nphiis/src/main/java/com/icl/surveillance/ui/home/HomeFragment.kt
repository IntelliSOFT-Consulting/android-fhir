package com.icl.surveillance.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.icl.surveillance.R
import com.icl.surveillance.adapters.HomeRecyclerViewAdapter
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FILE_PATH_KEY
import com.icl.surveillance.databinding.FragmentHomeBinding
import com.icl.surveillance.ui.notifications.NotificationActivity
import com.icl.surveillance.utils.FormatterClass

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val viewModel: HomeViewModel by viewModels()

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
        recyclerView.layoutManager = GridLayoutManager(context, 2)

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

    private fun handleUser() {
        val name = getUserNameFromDetails()
        val time = FormatterClass().getTimeOfDay()
        val fullText = "$time, \n\n$name"

        binding.apply {
            greetingText.text = time
            usernameText.text = safeText(name)
        }
    }

    private fun getUserNameFromDetails(): String {
        val user = FormatterClass().getSharedPref("fullNames", requireContext())
        return user ?: ""
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
     val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_bottom_sheet, null)

        dialog.setContentView(view)
        dialog.setCancelable(true)

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
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

        findNavController().navigate(
            R.id.action_navigation_home_to_childFragment,
            bundle
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

