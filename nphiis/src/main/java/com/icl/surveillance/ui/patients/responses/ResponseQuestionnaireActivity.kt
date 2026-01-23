package com.icl.surveillance.ui.patients.responses

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import cn.pedant.SweetAlert.SweetAlertDialog
import com.google.android.fhir.FhirEngine
import com.google.android.material.tabs.TabLayoutMediator
import com.google.gson.Gson
import com.icl.surveillance.R
import com.icl.surveillance.adapters.GroupPagerAdapter
import com.icl.surveillance.databinding.ActivityResponseQuestionnaireBinding
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.models.ChildItem
import com.icl.surveillance.models.OutputGroup
import com.icl.surveillance.models.OutputItem
import com.icl.surveillance.models.QuestionnaireItem
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.viewmodels.ResponseDetailsViewModel
import com.icl.surveillance.viewmodels.factories.ResponseDetailsViewModelFactory

class ResponseQuestionnaireActivity : AppCompatActivity() {
    private lateinit var groups: MutableList<OutputGroup>
    private lateinit var binding: ActivityResponseQuestionnaireBinding
    private lateinit var fhirEngine: FhirEngine
    private lateinit var patientDetailsViewModel: ResponseDetailsViewModel

    override fun onResume() {
        super.onResume()
        try {
            loadData()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityResponseQuestionnaireBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        fhirEngine = FhirApplication.fhirEngine(this@ResponseQuestionnaireActivity)
        val questionnaireId =
            FormatterClass().getSharedPref("resourceId", this@ResponseQuestionnaireActivity)

        println("Resource Id $questionnaireId")
        patientDetailsViewModel =
            ViewModelProvider(
                this,
                ResponseDetailsViewModelFactory(
                    this@ResponseQuestionnaireActivity.application, fhirEngine, "$questionnaireId"
                ),
            )
                .get(ResponseDetailsViewModel::class.java)
//        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
//            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
//            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
//            insets
//        }

        loadData()
    }

    private fun loadData() {
        val questionnaireId =
            FormatterClass().getSharedPref("resourceId", this@ResponseQuestionnaireActivity)

        groups =
            parseFromAssets(this, "mpox-supervisor-checklist.json").toMutableList()// this = Context
        patientDetailsViewModel.getInfoSummaryData("$questionnaireId")
        patientDetailsViewModel.liveSummaryData.observe(this) { data ->
            patientDetailsViewModel.hasQuestionnaireResponse = true

            invalidateOptionsMenu()
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.setDisplayShowHomeEnabled(true)

            groups.forEach { group ->
                // For each item inside the group

                group.items.forEach { outputItem ->
                    // Try to find a matching observation

                    val matchingObservation = data.observations.find { obs ->
                        obs.code == outputItem.linkId
                    }
                    if (matchingObservation != null) {
                        outputItem.value = matchingObservation.value
                    }

                }
            }
            val adapter = GroupPagerAdapter(this, groups, emptyList())
            binding.viewPager.adapter = adapter

            TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
                tab.text = adapter.getTabTitle(position)
            }.attach()
        }

    }

    fun parseFromAssets(context: Context, assets: String): List<OutputGroup> {
        var outputGroups: List<OutputGroup> = emptyList()

        try {
            if (assets.isNotEmpty()) {
                val jsonContent = context.assets.open(assets)
                    .bufferedReader()
                    .use { it.readText() }

                val gson = Gson()
                val questionnaire = gson.fromJson(jsonContent, QuestionnaireItem::class.java)

                outputGroups = questionnaire.item.map { group ->
                    OutputGroup(
                        linkId = group.linkId,
                        text = group.text,
                        type = group.type,
                        items = group.item?.flatMap { flattenItems(it) } ?: emptyList()
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("TAG", "File Error ${e.message}")
        }
        return outputGroups

    }

    fun flattenItems(
        item: ChildItem,
        parentConditions: Map<String, Pair<String, Boolean>> = emptyMap()
    ): List<OutputItem> {
        val currentConditions =
            mutableMapOf<String, Pair<String, Boolean>>().apply { putAll(parentConditions) }

        var enable = true
        var parentLink: String? = null
        var parentResponse: String? = null
        var enableOperator: String? = null
        item.enableWhen?.firstOrNull()?.let { condition ->
            parentLink = condition.question
            enableOperator = condition.operator
            val expectedAnswer = when {
                condition.answerCoding != null -> condition.answerCoding.display
                    ?: condition.answerCoding.code

                condition.answerString != null -> condition.answerString
                condition.answerBoolean != null -> condition.answerBoolean.toString()
                condition.answerDate != null -> condition.answerDate
                condition.answerInteger != null -> condition.answerInteger.toString()
                else -> null
            }
            parentResponse = expectedAnswer
            enable = false // assume not enabled unless condition is met at runtime
        }

        val children = item.item?.flatMap {
            flattenItems(it, currentConditions)
        } ?: emptyList()

        return if (item.type != "display") {

            val current = OutputItem(
                linkId = item.linkId,
                text = item.text,
                type = item.type,
                enable = enable,
                parentLink = parentLink,
                parentResponse = parentResponse,
                parentOperator = enableOperator
            )

            listOf(current) + children

        } else {
            children
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val hasResponse = patientDetailsViewModel.hasQuestionnaireResponse // set this as a flag
        menu.findItem(R.id.action_edit)?.isVisible = hasResponse
        return super.onPrepareOptionsMenu(menu)
    }

    // create a menu item here:
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_edit, menu)
        return true
    }

    // handle on menu click here
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_delete -> {
                SweetAlertDialog(this, SweetAlertDialog.WARNING_TYPE)
                    .setTitleText("Are you sure?")
                    .setContentText("You Won't be able to recover this record!")
                    .setConfirmText("Yes,delete it!")
                    .setConfirmClickListener { sDialog ->
                        Toast.makeText(this, "Coming soon", Toast.LENGTH_SHORT).show()
                        sDialog.dismissWithAnimation()
                    }
                    .show()

                return true
            }

            R.id.action_edit -> {
                val patientId =
                    FormatterClass().getSharedPref("patientId", this@ResponseQuestionnaireActivity)
                FormatterClass().saveSharedPref(
                    "questionnaire",
                    "mpox-supervisor-checklist.json",
                    this@ResponseQuestionnaireActivity
                )
                val intent = Intent(
                    this@ResponseQuestionnaireActivity,
                    EditChecklistActivity::class.java
                ).apply {
                    putExtra("questionnaire_id", patientId)
                }
                startActivity(intent)

                return true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }


}