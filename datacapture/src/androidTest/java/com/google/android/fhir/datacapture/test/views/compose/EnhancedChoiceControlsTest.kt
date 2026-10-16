/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir.datacapture.test.views.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.fhir.datacapture.R
import com.google.android.fhir.datacapture.views.compose.AutoCompleteDropDownItem
import com.google.android.fhir.datacapture.views.compose.CHOICE_SEARCH_FIELD_TAG
import com.google.android.fhir.datacapture.views.compose.DROP_DOWN_TEXT_FIELD_TAG
import com.google.android.fhir.datacapture.views.compose.OptionDialogSelect
import com.google.android.fhir.datacapture.views.compose.OptionSelectOption
import com.google.android.fhir.datacapture.views.compose.SelectedOptions
import com.google.android.fhir.datacapture.views.factories.DropDownAnswerOption
import com.google.common.truth.Truth.assertThat
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.StringType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnhancedChoiceControlsTest {
  @get:Rule val composeTestRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun options(vararg labels: String) =
    labels.map { label ->
      OptionSelectOption(
        Questionnaire.QuestionnaireItemAnswerOptionComponent().apply { value = StringType(label) },
        selected = false,
        context = context,
      )
    }

  @Test
  fun singleChoiceCommitsAndClosesOnTap() {
    var result: SelectedOptions? = null
    composeTestRule.setContent {
      var open by remember { mutableStateOf(true) }
      if (open) {
        OptionDialogSelect(
          context = context,
          title = AnnotatedString("Choose one"),
          multiSelect = false,
          otherOptionsAllowed = false,
          selectedOptions = SelectedOptions(options("First", "Second"), emptyList()),
          onDismiss = { open = false },
          onConfirm = { result = it },
          enhanced = true,
        )
      }
    }

    composeTestRule.onNodeWithText("Second").performClick()
    composeTestRule.waitForIdle()
    assertThat(result!!.options.filter { it.selected }.map { it.displayString })
      .containsExactly("Second")
    composeTestRule.onNodeWithText("Choose one").assertDoesNotExist()
  }

  @Test
  fun multipleChoiceSearchAndTapCommitWithoutDone() {
    var result: SelectedOptions? = null
    val labels = (1..10).map { "Choice $it" } + "Tenth match"
    composeTestRule.setContent {
      OptionDialogSelect(
        context = context,
        title = AnnotatedString("Choose several"),
        multiSelect = true,
        otherOptionsAllowed = false,
        selectedOptions = SelectedOptions(options(*labels.toTypedArray()), emptyList()),
        onDismiss = {},
        onConfirm = { result = it },
        enhanced = true,
      )
    }

    composeTestRule.onNodeWithTag(CHOICE_SEARCH_FIELD_TAG).performTextInput("TENTH")
    composeTestRule.onNodeWithText("Tenth match").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    assertThat(result!!.options.filter { it.selected }.map { it.displayString })
      .containsExactly("Tenth match")
    composeTestRule.onNodeWithText(context.getString(R.string.done)).assertIsDisplayed()
  }

  @Test
  fun selectedDropdownCanBeReplacedByTyping() {
    val first = DropDownAnswerOption("1", "First")
    val second = DropDownAnswerOption("2", "Second")
    var selected: DropDownAnswerOption? = null
    composeTestRule.setContent {
      val focusManager = LocalFocusManager.current
      Column {
        AutoCompleteDropDownItem(
          modifier = Modifier,
          enabled = true,
          readOnly = true,
          enhanced = true,
          selectedOption = first,
          options = listOf(first, second),
          onDropDownAnswerOptionSelected = { selected = it },
        )
        TextButton(onClick = { focusManager.clearFocus() }) { Text("Close search") }
      }
    }

    composeTestRule.onNodeWithTag(DROP_DOWN_TEXT_FIELD_TAG).performClick()
    composeTestRule.onNodeWithTag(DROP_DOWN_TEXT_FIELD_TAG).performTextReplacement("missing")
    composeTestRule.onNodeWithText("Close search").performClick()
    composeTestRule.onNodeWithTag(DROP_DOWN_TEXT_FIELD_TAG).assertTextEquals("First")
    composeTestRule.onNodeWithTag(DROP_DOWN_TEXT_FIELD_TAG).performClick()
    composeTestRule.onNodeWithTag(DROP_DOWN_TEXT_FIELD_TAG).performTextReplacement("fir")
    composeTestRule.onNodeWithText("First").performClick()
    composeTestRule.onNodeWithTag(DROP_DOWN_TEXT_FIELD_TAG).assertTextEquals("First")
    composeTestRule.onNodeWithTag(DROP_DOWN_TEXT_FIELD_TAG).performClick()
    composeTestRule.onNodeWithTag(DROP_DOWN_TEXT_FIELD_TAG).performTextReplacement("sec")
    composeTestRule.onNodeWithText("Second").performClick()
    composeTestRule.waitForIdle()
    assertThat(selected).isEqualTo(second)
  }

  @Test
  fun otherTextSurvivesImmediateChoiceUpdatesAndCommitsOnDone() {
    var result: SelectedOptions? = null
    val initial = SelectedOptions(options("First"), emptyList())
    composeTestRule.setContent {
      var selected by remember { mutableStateOf(initial) }
      var open by remember { mutableStateOf(true) }
      if (open) {
        OptionDialogSelect(
          context = context,
          title = AnnotatedString("Choose several"),
          multiSelect = true,
          otherOptionsAllowed = true,
          selectedOptions = selected,
          onDismiss = { open = false },
          onConfirm = {
            selected = it
            result = it
          },
          enhanced = true,
        )
      }
    }

    composeTestRule.onNodeWithText("First").performClick()
    composeTestRule.onNodeWithText(context.getString(R.string.open_choice_other)).performClick()
    composeTestRule.onNode(hasSetTextAction()).performTextInput("Custom")
    composeTestRule.onNodeWithText("First").performClick()
    composeTestRule.runOnIdle { assertThat(result!!.otherOptions).isEmpty() }
    composeTestRule.onNode(hasSetTextAction()).assertTextEquals("Custom")
    composeTestRule.onNodeWithText(context.getString(R.string.done)).performClick()
    composeTestRule.runOnIdle { assertThat(result!!.otherOptions).containsExactly("Custom") }
  }

  @Test
  fun abandoningOtherKeepsTheExistingSingleAnswer() {
    val initial = SelectedOptions(options("First").map { it.copy(selected = true) }, emptyList())
    var result = initial
    val open = mutableStateOf(true)
    composeTestRule.setContent {
      if (open.value) {
        OptionDialogSelect(
          context = context,
          title = AnnotatedString("Choose one"),
          multiSelect = false,
          otherOptionsAllowed = true,
          selectedOptions = initial,
          onDismiss = { open.value = false },
          onConfirm = { result = it },
          enhanced = true,
        )
      }
    }

    composeTestRule.onNodeWithText(context.getString(R.string.open_choice_other)).performClick()
    composeTestRule.runOnIdle { open.value = false }
    composeTestRule.runOnIdle { assertThat(result).isSameInstanceAs(initial) }
    composeTestRule.onNodeWithText("Choose one").assertDoesNotExist()
  }
}
