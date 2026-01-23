package com.icl.surveillance.utils

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.widget.Button
import com.google.android.fhir.datacapture.extensions.layoutInflater
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.common.reflect.TypeToken
import com.google.gson.Gson
import com.icl.surveillance.R
import com.icl.surveillance.models.QuestionnaireAnswer
import com.icl.surveillance.utils.Constants.FIRST_LAUNCH_KEY
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class FormatterClass {
  private val dateInverseFormatSeconds: SimpleDateFormat =
      SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
  private val genericDate: SimpleDateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.ENGLISH)

  private val PREFSNAME = "facility_cache"
  private val KEYPREFIX = "facilities_"

  private val PREFNAME = "location_cache"
  private val KEYLOCATIONFACILITYMAP = "location_facility_map"

  private val KEY_SYNC_DONE = "sync_done"

  fun isSyncDone(context: Context): Boolean {
    return context
        .getSharedPreferences(PREFNAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_SYNC_DONE, false)
  }

  fun setSyncDone(context: Context) {
    context
        .getSharedPreferences(PREFNAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_SYNC_DONE, true)
        .apply()
  }

  fun saveFacilityIdsForWard(context: Context, locationId: String, facilityIds: List<String>) {
    val prefs = context.getSharedPreferences(PREFNAME, Context.MODE_PRIVATE)
    val existingJson = prefs.getString(KEYLOCATIONFACILITYMAP, "{}")
    val jsonObject = JSONObject(existingJson ?: "{}")

    jsonObject.put(locationId, JSONArray(facilityIds))
    prefs.edit().putString(KEYLOCATIONFACILITYMAP, jsonObject.toString()).apply()
  }

  fun getFacilityIdsForWard(context: Context, locationId: String): List<String> {
    val prefs = context.getSharedPreferences(PREFNAME, Context.MODE_PRIVATE)
    val existingJson = prefs.getString(KEYLOCATIONFACILITYMAP, "{}")
    val jsonObject = JSONObject(existingJson ?: "{}")

    return if (jsonObject.has(locationId)) {
      val jsonArray = jsonObject.getJSONArray(locationId)
      List(jsonArray.length()) { jsonArray.getString(it) }
    } else emptyList()
  }

  private fun prefs(context: Context): SharedPreferences =
      context.getSharedPreferences(PREFSNAME, Context.MODE_PRIVATE)

  fun saveFacilityIds(context: Context, wardId: String, facilityIds: List<String>) {
    val json = Gson().toJson(facilityIds)
    prefs(context).edit().putString("$KEYPREFIX$wardId", json).apply()
  }

  fun extractStructuredAnswersOnlyFromItems(json: JSONObject): List<QuestionnaireAnswer> {
    val results = mutableListOf<QuestionnaireAnswer>()

    fun processItems(items: JSONArray) {
      for (i in 0 until items.length()) {
        val item = items.getJSONObject(i)
        val linkId = item.optString("linkId", "")
        val text = item.optString("text", "")

        if (item.has("answer")) {
          val answers = item.getJSONArray("answer")
          val valueList = mutableListOf<String>()

          for (j in 0 until answers.length()) {
            val answerObj = answers.getJSONObject(j)

            val value =
                when {
                  answerObj.has("valueString") -> answerObj.getString("valueString")
                  answerObj.has("valueInteger") -> answerObj.optString("valueInteger", "")

                  answerObj.has("valueDate") -> answerObj.optString("valueDate", "")
                  answerObj.has("valueDateTime") -> answerObj.optString("valueDateTime", "")

                  answerObj.has("valueBoolean") -> answerObj.optString("valueBoolean", "")

                  answerObj.has("valueDecimal") -> answerObj.optString("valueDecimal", "")

                  answerObj.has("valueCoding") -> {
                    val coding = answerObj.getJSONObject("valueCoding")
                    coding.optString("display", coding.optString("code", ""))
                  }

                  answerObj.has("valueReference") -> {
                    val ref = answerObj.getJSONObject("valueReference")
                    ref.optString("display", ref.optString("reference", ""))
                  }

                  else -> null
                }

            if (!value.isNullOrBlank()) {
              valueList.add(value)
            }
          }

          if (valueList.isNotEmpty()) {
            // Join multiple values with comma
            results.add(QuestionnaireAnswer(linkId, text, valueList.joinToString(", ")))
          }
        }

        if (item.has("item")) {
          processItems(item.getJSONArray("item"))
        }
      }
    }

    if (json.has("item")) {
      processItems(json.getJSONArray("item"))
    }

    return results
  }

  fun getFacilityIds(context: Context, wardId: String): List<String>? {
    val json = prefs(context).getString("$KEYPREFIX$wardId", null)
    return json?.let { Gson().fromJson(it, object : TypeToken<List<String>>() {}.type) }
  }

  fun String.toSlug(): String {
    return this.trim() // remove leading/trailing spaces
        .lowercase() // make all lowercase
        .replace("[^a-z0-9\\s-]".toRegex(), "") // remove special characters
        .replace("\\s+".toRegex(), "-") // replace spaces with hyphens
        .replace("-+".toRegex(), "-") // collapse multiple hyphens
  }

  fun generateInitials(source: String): String {
    if (source.isEmpty()) return "XXX"

    val customAbbreviations =
        mapOf(
            "LOITOKITOK" to "LTK",
        )

    val words = source.trim().split("\\s+".toRegex())

    // Single word case
    if (words.size == 1) {
      val word = words[0].uppercase()
      return customAbbreviations[word] ?: word.take(3).padEnd(3, 'X')
    }

    // Multiple words - check for custom words
    val customWordIndex = words.indexOfFirst { it.uppercase() in customAbbreviations }

    return if (customWordIndex != -1) {
          // We found a custom word - use its abbreviation
          val customWord = words[customWordIndex]
          val abbreviation = customAbbreviations[customWord.uppercase()]!!

          // Combine abbreviation with other words
          val otherWords = words.filterIndexed { index, _ -> index != customWordIndex }
          buildWithCustomAbbreviation(abbreviation, otherWords)
        } else {
          // No custom words - use normal logic
          buildNormalInitials(words)
        }
        .uppercase()
  }

  private fun buildWithCustomAbbreviation(abbreviation: String, otherWords: List<String>): String {
    return when {
      otherWords.isEmpty() -> abbreviation.take(3)
      otherWords.size == 1 -> (abbreviation.take(2) + otherWords[0].take(1)).take(3)
      else -> (abbreviation.take(1) + otherWords[0].take(1) + otherWords[1].take(1)).take(3)
    }.padEnd(3, 'X')
  }

  private fun buildNormalInitials(words: List<String>): String {
    return when (words.size) {
      1 -> words[0].take(3)
      2 -> (words[0].take(2) + words[1].take(1))
      else -> words.take(3).joinToString("") { it.first().toString() }
    }.padEnd(3, 'X')
  }

  fun saveSharedPref(key: String, value: String, context: Context) {
    val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(context.getString(R.string.app_name), MODE_PRIVATE)
    val editor = sharedPreferences.edit()
    editor.putString(key, value)
    editor.apply()
  }

  fun getSharedPref(key: String, context: Context): String? {
    val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(context.getString(R.string.app_name), MODE_PRIVATE)
    return sharedPreferences.getString(key, null)
  }

  fun deleteSharedPref(key: String, context: Context) {
    val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(context.getString(R.string.app_name), MODE_PRIVATE)
    val editor = sharedPreferences.edit()
    editor.remove(key)
    editor.apply()
  }

  fun clearCache(context: Context) {
    val prefsLocal = context.getSharedPreferences(PREFNAME, Context.MODE_PRIVATE)
    prefs(context).edit().clear().apply()
    prefsLocal.edit().clear().apply()

    val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(context.getString(R.string.app_name), MODE_PRIVATE)
    val editor = sharedPreferences.edit()
    editor.clear()
    editor.apply()
  }

  fun isFirstLaunch(context: Context): Boolean {
    val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(context.getString(R.string.app_name), MODE_PRIVATE)
    return sharedPreferences.getBoolean(FIRST_LAUNCH_KEY, true)
  }

  fun setFirstLaunchCompleted(context: Context) {
    val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(context.getString(R.string.app_name), MODE_PRIVATE)
    val editor = sharedPreferences.edit()
    editor.putBoolean(FIRST_LAUNCH_KEY, false)
    editor.apply()
  }

  /**
   * Formats the provided [date] into a string using the pattern `yyyy-MM-dd HH:mm:ss` and the
   * English locale.
   */
  fun formatDateTime(date: Date): String {
    return dateInverseFormatSeconds.format(date)
  }

  fun formatProvidedDate(sourceDate: Date): Date {
    val formatter = SimpleDateFormat("MM/dd/yyyy", Locale.US)

    // Step 1: Format to string
    val formattedString = formatter.format(sourceDate)

    // Step 2: Parse back into Date
    return formatter.parse(formattedString)!!
  }

  fun formatToMMddyyyy(date: Date): Date {
    val formatter = SimpleDateFormat("MM/dd/yyyy", Locale.US)

    val formatted = formatter.format(date) // String
    return formatter.parse(formatted)!! // back to Date
  }

  fun getTimeOfDay(): String {

    val currentTime = LocalTime.now()
    return when (currentTime.hour) {
      in 5..11 -> "Good Morning"
      in 12..16 -> "Good Afternoon"
      in 17..20 -> "Good Evening"
      else -> "Good Night"
    }
  }

  fun showComingSoon(context: Context) {
    try {
      val dialog = BottomSheetDialog(context)
      val view = context.layoutInflater.inflate(R.layout.dialog_bottom_sheet, null)

      dialog.setContentView(view)
      dialog.setCancelable(true)

      view.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
      dialog.show()
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }
}
