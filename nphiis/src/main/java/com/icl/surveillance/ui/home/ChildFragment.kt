package com.icl.surveillance.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.icl.surveillance.R
import com.icl.surveillance.adapters.DiseasesRecyclerViewAdapter
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FILE_PATH_KEY
import com.icl.surveillance.databinding.FragmentChildBinding
import com.icl.surveillance.utils.FormatterClass
import kotlin.getValue

// TODO: Rename parameter arguments, choose names that match
// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

/**
 * A simple [Fragment] subclass. Use the [ChildFragment.newInstance] factory method to create an
 * instance of this fragment.
 */
class ChildFragment : Fragment() {
  // TODO: Rename and change types of parameters
  private var param1: String? = null
  private var param2: String? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    arguments?.let {
      param1 = it.getString(ARG_PARAM1)
      param2 = it.getString(ARG_PARAM2)
    }
  }

  private var _binding: FragmentChildBinding? = null

  private val viewModel: HomeViewModel by viewModels()

  // This property is only valid between onCreateView and
  // onDestroyView.
  private val binding
    get() = _binding!!

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?,
  ): View? {
    // Inflate the layout for this fragment
    _binding = FragmentChildBinding.inflate(inflater, container, false)
    val root: View = binding.root

    return root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    var titleName = FormatterClass().getSharedPref("title", requireContext())
    var stage = FormatterClass().getSharedPref("stage", requireContext())

    val activity = requireActivity() as AppCompatActivity
    activity.supportActionBar?.apply {
      title = ""
      setDisplayHomeAsUpEnabled(true)
      setDisplayShowHomeEnabled(true)
    }

    // Let the Fragment receive menu callbacks
    setHasOptionsMenu(true)

    binding.apply {
      titleName =
          when (titleName) {
            "Immediate Notifiable Diseases Reporting Tool" ->
                "Integrated Case Based Surveillance form"
            else -> titleName
          }
      greeting.text = titleName?.replace("\n", " ")
    }
    val adapter =
        DiseasesRecyclerViewAdapter(::onItemClick).apply {
          if (stage != null) {
            when (stage) {
              "0" -> {
                submitList(viewModel.getNotifiableList())
              }

              "1" -> {
                submitList(viewModel.getMassList())
              }

              "2" -> {
                submitList(viewModel.getCaseList())
              }

              "3" -> {
                submitList(viewModel.getSocialList())
              }

              "100" -> {
                submitList(viewModel.getMOHList())
              }

              else -> {
                
              }
            }
          }
        }
    val recyclerView = requireView().findViewById<RecyclerView>(R.id.sdcLayoutsRecyclerView)
    recyclerView.adapter = adapter
    recyclerView.layoutManager = GridLayoutManager(context, 2)
  }

  private fun onItemClick(layout: HomeViewModel.Diseases) {
    val title = context?.getString(layout.textId) ?: ""
    println("Child Clicked ***** ${layout.level}")
    when (layout.level) {
      0 -> {
        val bundle = Bundle().apply { putString(QUESTIONNAIRE_FILE_PATH_KEY, "add-case.json") }

        FormatterClass().saveSharedPref("childTitle", title, requireContext())
        FormatterClass().saveSharedPref("childStage", "6", requireContext())

        findNavController().navigate(R.id.action_childFragment_to_singleCaseFragment, bundle)
      }
      13 -> {
        val bundle = Bundle().apply { putString(QUESTIONNAIRE_FILE_PATH_KEY, "add-case.json") }

        FormatterClass().saveSharedPref("childTitle", title, requireContext())
        FormatterClass().saveSharedPref("childStage", "7", requireContext())

        findNavController().navigate(R.id.action_childFragment_to_singleCaseFragment, bundle)
      }

      1 -> {
        val bundle = Bundle().apply { putString(QUESTIONNAIRE_FILE_PATH_KEY, "moh505.json") }

        FormatterClass().saveSharedPref("childTitle", title, requireContext())
        FormatterClass().saveSharedPref("childStage", "100", requireContext())

        findNavController().navigate(R.id.action_childFragment_to_singleCaseFragment, bundle)
      }

      30 -> {

        val bundle = Bundle().apply { putString(QUESTIONNAIRE_FILE_PATH_KEY, "add-case.json") }

        FormatterClass()
            .saveSharedPref(
                "grandTitle",
                "Social Listening and Rumor Tracking Tool",
                requireContext(),
            )
        FormatterClass().saveSharedPref("childStage", "6", requireContext())

        findNavController().navigate(R.id.action_childFragment_to_caseSelectionFragment, bundle)
      }

      31 -> {

        val bundle = Bundle().apply { putString(QUESTIONNAIRE_FILE_PATH_KEY, "add-case.json") }

        FormatterClass().saveSharedPref("grandTitle", "Social Investigation Form", requireContext())
        FormatterClass().saveSharedPref("childStage", "60", requireContext())

        findNavController().navigate(R.id.action_childFragment_to_caseSelectionFragment, bundle)
      }

      20 -> {
        val bundle = Bundle().apply { putString(QUESTIONNAIRE_FILE_PATH_KEY, "add-case.json") }

        FormatterClass().saveSharedPref("grandTitle", title, requireContext())
        FormatterClass().saveSharedPref("childStage", "6", requireContext())

        findNavController().navigate(R.id.action_childFragment_to_caseSelectionFragment, bundle)
      }

      else -> {

       val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_bottom_sheet, null)

        dialog.setContentView(view)
        dialog.setCancelable(true)



        view.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
      }
    }
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
      android.R.id.home -> {
        // Handle the back button in the toolbar
        requireActivity().onBackPressedDispatcher.onBackPressed()
        true
      }

      else -> super.onOptionsItemSelected(item)
    }
  }

  companion object {
    /**
     * Use this factory method to create a new instance of this fragment using the provided
     * parameters.
     *
     * @param param1 Parameter 1.
     * @param param2 Parameter 2.
     * @return A new instance of fragment ChildFragment.
     */
    // TODO: Rename and change types and number of parameters
    @JvmStatic
    fun newInstance(param1: String, param2: String) =
        ChildFragment().apply {
          arguments =
              Bundle().apply {
                putString(ARG_PARAM1, param1)
                putString(ARG_PARAM2, param2)
              }
        }
  }
}
