package com.icl.surveillance.fhir

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.math.BigDecimal
import org.hl7.fhir.r4.hapi.ctx.HapiWorkerContext
import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.Enumeration
import org.hl7.fhir.r4.model.Expression
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.InstantType
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.PositiveIntType
import org.hl7.fhir.r4.model.PrimitiveType
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.TimeType
import org.hl7.fhir.r4.model.Type
import org.hl7.fhir.r4.model.UnsignedIntType
import org.hl7.fhir.r4.model.UriType
import org.hl7.fhir.r4.utils.FHIRPathEngine

/**
 * Dedicated template-based extractor for NPHIIS questionnaires that use the SDC
 * `templateExtractBundle` pattern.
 *
 * The current implementation intentionally focuses on the subset needed by `add-case.json`:
 * object-level `templateExtractContext` and primitive-level `templateExtractValue`.
 */
class SdcTemplateExtractBundleExtractor {

  private val fhirContext = FhirContext.forCached(FhirVersionEnum.R4)
  private val parser = fhirContext.newJsonParser()
  private val fhirPathEngine =
    FHIRPathEngine(HapiWorkerContext(fhirContext, fhirContext.validationSupport)).apply {
      hostServices = HostServices
    }

  fun supports(questionnaire: Questionnaire): Boolean {
    return questionnaire.getExtensionByUrl(TEMPLATE_EXTRACT_BUNDLE_URL)?.value is Reference
  }

  fun extract(
    questionnaire: Questionnaire,
    questionnaireResponse: QuestionnaireResponse,
  ): Bundle {
    val bundleTemplate = resolveTemplateBundle(questionnaire)
    val templateJson =
      JsonParser.parseString(parser.encodeResourceToString(bundleTemplate)).asJsonObject.deepCopy()
    val extractedBundles =
      processObjectNode(
        templateJson,
        listOf(TemplateContext(base = questionnaireResponse)),
        questionnaireResponse,
      )

    val extractedBundleJson =
      extractedBundles.firstOrNull()
        ?: throw IllegalStateException("Template extraction did not produce a bundle.")

    extractedBundleJson.remove("id")
    extractedBundleJson.remove("contained")
    extractedBundleJson.addProperty("resourceType", "Bundle")
    extractedBundleJson.addProperty("type", Bundle.BundleType.TRANSACTION.toCode())

    val bundle =
      parser.parseResource(Bundle::class.java, extractedBundleJson.toString()).apply {
        type = Bundle.BundleType.TRANSACTION
        id = null
      }

    bundle.entry.removeAll { it.resource == null }
    return bundle
  }

  private fun resolveTemplateBundle(questionnaire: Questionnaire): Bundle {
    val extension =
      questionnaire.getExtensionByUrl(TEMPLATE_EXTRACT_BUNDLE_URL)
        ?: throw IllegalStateException("Questionnaire does not define templateExtractBundle.")
    val reference = (extension.value as? Reference)?.reference
      ?: throw IllegalStateException("templateExtractBundle must reference a contained Bundle.")
    val bundleId = reference.removePrefix("#")
    return questionnaire.contained
      .filterIsInstance<Bundle>()
      .firstOrNull { it.idPart.removePrefix("#") == bundleId }
      ?: throw IllegalStateException("Unable to find contained Bundle template $reference.")
  }

  private fun processObjectNode(
    templateObject: JsonObject,
    contexts: List<TemplateContext>,
    questionnaireResponse: QuestionnaireResponse,
  ): List<JsonObject> {
    val scopedContexts = applyObjectContext(templateObject, contexts, questionnaireResponse)
    if (scopedContexts.isEmpty()) {
      return emptyList()
    }

    return scopedContexts.mapNotNull { context ->
      val workingObject = templateObject.deepCopy()
      removeTemplateExtensions(workingObject)

      val fieldNames = workingObject.keySet().toList()
      fieldNames
        .filter { !it.startsWith(PRIMITIVE_COMPANION_PREFIX) }
        .forEach { fieldName ->
          when (val value = workingObject.get(fieldName)) {
            is JsonArray -> {
              val companionField = "$PRIMITIVE_COMPANION_PREFIX$fieldName"
              val companion = workingObject.get(companionField)
              if (
                isPrimitiveArrayWithCompanion(value, companion) &&
                  companion != null &&
                  companion.isJsonArray
              ) {
                val processed =
                  processPrimitiveArray(
                    value,
                    companion.asJsonArray,
                    context,
                    questionnaireResponse,
                  )
                updatePrimitiveArrayFields(workingObject, fieldName, companionField, processed)
              } else {
                val processedArray = processArrayNode(value, context, questionnaireResponse)
                if (processedArray.isEmpty()) {
                  workingObject.remove(fieldName)
                } else {
                  workingObject.add(fieldName, processedArray)
                }
              }
            }

            is JsonObject -> {
              val processedChildren =
                processObjectNode(value, listOf(context), questionnaireResponse)
              when {
                processedChildren.isEmpty() -> workingObject.remove(fieldName)
                processedChildren.size == 1 -> workingObject.add(fieldName, processedChildren.first())
                else -> {
                  val replacement = JsonArray()
                  processedChildren.forEach(replacement::add)
                  workingObject.add(fieldName, replacement)
                }
              }
            }

            else -> {
              val companionField = "$PRIMITIVE_COMPANION_PREFIX$fieldName"
              val companion = workingObject.get(companionField)
              if (companion != null && companion.isJsonObject) {
                val processedPrimitive =
                  processPrimitiveField(
                    value,
                    companion.asJsonObject,
                    context,
                    questionnaireResponse,
                  )
                if (processedPrimitive == null) {
                  workingObject.remove(fieldName)
                  workingObject.remove(companionField)
                } else {
                  workingObject.add(fieldName, processedPrimitive.value)
                  if (processedPrimitive.companion != null) {
                    workingObject.add(companionField, processedPrimitive.companion)
                  } else {
                    workingObject.remove(companionField)
                  }
                }
              }
            }
          }
        }

      // Handle primitive companion fields that create a value from scratch.
      fieldNames
        .filter { it.startsWith(PRIMITIVE_COMPANION_PREFIX) }
        .filter { !workingObject.has(it.removePrefix(PRIMITIVE_COMPANION_PREFIX)) }
        .forEach { companionField ->
          val companion = workingObject.get(companionField)
          if (companion == null || !companion.isJsonObject) {
            return@forEach
          }

          val fieldName = companionField.removePrefix(PRIMITIVE_COMPANION_PREFIX)
          val processedPrimitive =
            processPrimitiveField(JsonNull.INSTANCE, companion.asJsonObject, context, questionnaireResponse)
          if (processedPrimitive == null) {
            workingObject.remove(companionField)
          } else {
            workingObject.add(fieldName, processedPrimitive.value)
            if (processedPrimitive.companion != null) {
              workingObject.add(companionField, processedPrimitive.companion)
            } else {
              workingObject.remove(companionField)
            }
          }
        }

      workingObject.takeUnless { it.isJsonNullLikeObject() }
    }
  }

  private fun processArrayNode(
    templateArray: JsonArray,
    context: TemplateContext,
    questionnaireResponse: QuestionnaireResponse,
  ): JsonArray {
    val processed = JsonArray()
    templateArray.forEach { element ->
      when {
        element.isJsonObject -> {
          processObjectNode(element.asJsonObject, listOf(context), questionnaireResponse)
            .forEach(processed::add)
        }

        element.isJsonArray -> {
          processed.add(processArrayNode(element.asJsonArray, context, questionnaireResponse))
        }

        else -> processed.add(element.deepCopy())
      }
    }
    return processed
  }

  private fun processPrimitiveArray(
    valueArray: JsonArray,
    companionArray: JsonArray,
    context: TemplateContext,
    questionnaireResponse: QuestionnaireResponse,
  ): PrimitiveArrayProcessingResult {
    val newValues = JsonArray()
    val newCompanions = JsonArray()

    val maxSize = maxOf(valueArray.size(), companionArray.size())
    for (index in 0 until maxSize) {
      val value = if (index < valueArray.size()) valueArray[index] else JsonNull.INSTANCE
      val companion =
        if (index < companionArray.size() && companionArray[index].isJsonObject) {
          companionArray[index].asJsonObject
        } else {
          null
        }

      if (companion == null) {
        if (value !is JsonNull) {
          newValues.add(value.deepCopy())
        }
        continue
      }

      val processed = processPrimitiveField(value, companion, context, questionnaireResponse)
      if (processed != null) {
        newValues.add(processed.value)
        newCompanions.add(processed.companion ?: JsonObject())
      }
    }

    return PrimitiveArrayProcessingResult(values = newValues, companions = newCompanions)
  }

  private fun processPrimitiveField(
    templateValue: JsonElement,
    companionObject: JsonObject,
    context: TemplateContext,
    questionnaireResponse: QuestionnaireResponse,
  ): PrimitiveFieldResult? {
    val contextExpression = findTemplateExpression(companionObject, TEMPLATE_EXTRACT_CONTEXT_URL)
    val valueExpression = findTemplateExpression(companionObject, TEMPLATE_EXTRACT_VALUE_URL)
    val scopedContexts =
      if (contextExpression == null) {
        listOf(context)
      } else {
        evaluateExpression(
            contextExpression.expression,
            context.base,
            questionnaireResponse,
            context.variables,
          )
          .map { result ->
            TemplateContext(
              base = result,
              variables =
                if (contextExpression.name != null) {
                  context.variables + (contextExpression.name to result)
                } else {
                  context.variables
                },
            )
          }
      }

    if (scopedContexts.isEmpty()) {
      return null
    }

    val valueElement =
      if (valueExpression == null) {
        templateValue.deepCopy()
      } else {
        val values =
          scopedContexts.flatMap { scopedContext ->
            evaluateExpression(
              valueExpression.expression,
              scopedContext.base,
              questionnaireResponse,
              scopedContext.variables,
            )
          }
        if (values.isEmpty()) {
          return null
        }
        convertBaseToJson(values.first())
      }

    val strippedCompanion = companionObject.deepCopy()
    removeTemplateExtensions(strippedCompanion)
    return PrimitiveFieldResult(
      value = valueElement,
      companion = strippedCompanion.takeUnless { it.isJsonNullLikeObject() },
    )
  }

  private fun applyObjectContext(
    templateObject: JsonObject,
    contexts: List<TemplateContext>,
    questionnaireResponse: QuestionnaireResponse,
  ): List<TemplateContext> {
    val contextExpression = findTemplateExpression(templateObject, TEMPLATE_EXTRACT_CONTEXT_URL)
      ?: return contexts

    return contexts.flatMap { context ->
      evaluateExpression(
        contextExpression.expression,
        context.base,
        questionnaireResponse,
        context.variables,
      ).map { result ->
        TemplateContext(
          base = result,
          variables =
            if (contextExpression.name != null) {
              context.variables + (contextExpression.name to result)
            } else {
              context.variables
            },
        )
      }
    }
  }

  private fun findTemplateExpression(
    owner: JsonObject,
    url: String,
  ): TemplateExpression? {
    val extensions = owner.getAsJsonArray("extension") ?: return null
    return extensions
      .mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
      .firstNotNullOfOrNull { extension ->
        if (extension.get("url")?.asString != url) {
          null
        } else {
          when {
            extension.has("valueString") ->
              TemplateExpression(expression = extension.get("valueString").asString)
            extension.has("valueExpression") -> {
              val expression = extension.getAsJsonObject("valueExpression")
              TemplateExpression(
                expression = expression.get("expression")?.asString.orEmpty(),
                name = expression.get("name")?.asString,
              )
            }

            else -> null
          }
        }
      }
  }

  private fun removeTemplateExtensions(owner: JsonObject) {
    val extensions = owner.getAsJsonArray("extension") ?: return
    val filteredExtensions = JsonArray()
    extensions
      .mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
      .filterNot { extension ->
        extension.get("url")?.asString in TEMPLATE_EXTENSION_URLS
      }
      .forEach(filteredExtensions::add)

    if (filteredExtensions.size() == 0) {
      owner.remove("extension")
    } else {
      owner.add("extension", filteredExtensions)
    }
  }

  private fun evaluateExpression(
    expression: String,
    base: Base,
    questionnaireResponse: QuestionnaireResponse,
    variables: Map<String, Base?>,
  ): List<Base> {
    if (expression.isBlank()) {
      return emptyList()
    }

    return fhirPathEngine.evaluate(variables, questionnaireResponse, null, base, expression)
  }

  private fun convertBaseToJson(base: Base): JsonElement {
    return when (base) {
      is BooleanType -> JsonPrimitive(base.booleanValue())
      is IntegerType -> JsonPrimitive(base.value)
      is PositiveIntType -> JsonPrimitive(base.value)
      is UnsignedIntType -> JsonPrimitive(base.value)
      is DecimalType -> JsonPrimitive(base.value ?: BigDecimal.ZERO)
      is StringType -> JsonPrimitive(base.value)
      is UriType -> JsonPrimitive(base.value)
      is IdType -> JsonPrimitive(base.value)
      is DateType -> JsonPrimitive(base.valueAsString)
      is DateTimeType -> JsonPrimitive(base.valueAsString)
      is InstantType -> JsonPrimitive(base.valueAsString)
      is TimeType -> JsonPrimitive(base.valueAsString)
      is PrimitiveType<*> -> JsonPrimitive(base.valueAsString)
      is Enumeration<*> -> JsonPrimitive(base.primitiveValue())
      is Coding -> JsonPrimitive(base.display ?: base.code ?: "")
      is Reference -> JsonPrimitive(base.display ?: base.reference ?: "")
      is Type -> JsonPrimitive(base.primitiveValue() ?: base.toString())
      else -> JsonPrimitive(base.primitiveValue() ?: base.toString())
    }
  }

  private fun updatePrimitiveArrayFields(
    owner: JsonObject,
    fieldName: String,
    companionField: String,
    processed: PrimitiveArrayProcessingResult,
  ) {
    if (processed.values.size() == 0) {
      owner.remove(fieldName)
      owner.remove(companionField)
      return
    }

    owner.add(fieldName, processed.values)
    if (processed.companions.size() == 0 || processed.companions.all { it.asJsonObject.isJsonNullLikeObject() }) {
      owner.remove(companionField)
    } else {
      owner.add(companionField, processed.companions)
    }
  }

  private fun isPrimitiveArrayWithCompanion(
    valueArray: JsonArray,
    companion: JsonElement?,
  ): Boolean {
    return companion?.isJsonArray == true &&
      valueArray.all { !it.isJsonObject && !it.isJsonArray }
  }

  private fun JsonObject.isJsonNullLikeObject(): Boolean {
    return entrySet().isEmpty()
  }

  private data class TemplateContext(
    val base: Base,
    val variables: Map<String, Base?> = emptyMap(),
  )

  private data class TemplateExpression(
    val expression: String,
    val name: String? = null,
  )

  private data class PrimitiveFieldResult(
    val value: JsonElement,
    val companion: JsonObject?,
  )

  private data class PrimitiveArrayProcessingResult(
    val values: JsonArray,
    val companions: JsonArray,
  )

  private object HostServices : FHIRPathEngine.IEvaluationContext {
    override fun resolveConstant(
      appContext: Any?,
      name: String?,
      beforeContext: Boolean,
    ): List<Base>? {
      return ((appContext as? Map<*, *>)?.get(name) as? Base)?.let(::listOf) ?: emptyList()
    }

    override fun resolveConstantType(appContext: Any?, name: String?) =
      throw UnsupportedOperationException()

    override fun log(argument: String?, focus: MutableList<Base>?) =
      throw UnsupportedOperationException()

    override fun resolveFunction(functionName: String?) =
      throw UnsupportedOperationException()

    override fun checkFunction(
      appContext: Any?,
      functionName: String?,
      parameters: MutableList<org.hl7.fhir.r4.model.TypeDetails>?,
    ) = throw UnsupportedOperationException()

    override fun executeFunction(
      appContext: Any?,
      focus: MutableList<Base>?,
      functionName: String?,
      parameters: MutableList<MutableList<Base>>?,
    ) = throw UnsupportedOperationException()

    override fun resolveReference(appContext: Any?, url: String?, refContext: Base?) =
      throw UnsupportedOperationException()

    override fun conformsToProfile(appContext: Any?, item: Base?, url: String?) =
      throw UnsupportedOperationException()

    override fun resolveValueSet(appContext: Any?, url: String?) =
      throw UnsupportedOperationException()
  }

  companion object {
    const val TEMPLATE_EXTRACT_BUNDLE_URL =
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractBundle"
    const val TEMPLATE_EXTRACT_CONTEXT_URL =
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractContext"
    const val TEMPLATE_EXTRACT_VALUE_URL =
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue"

    private const val PRIMITIVE_COMPANION_PREFIX = "_"

    private val TEMPLATE_EXTENSION_URLS =
      setOf(
        TEMPLATE_EXTRACT_CONTEXT_URL,
        TEMPLATE_EXTRACT_VALUE_URL,
      )
  }
}
