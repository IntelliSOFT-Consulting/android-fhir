package com.icl.surveillance.utils

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Lists every field of a JSON record as (label, value) rows, in the order received, so the
 * healthcare worker sees all the information a registry returned — including fields the app does
 * not use and ones added later.
 *
 * - nested objects become "Meta – Version ID"
 * - identification lists become one row per document ("SHA Number" -> "SHA-2026-000235")
 * - other lists of objects become "Item 1 – …", lists of values are joined with commas
 * - null / empty values are left out
 */
object JsonDetails {

    fun rows(obj: JsonObject, skip: Set<String> = emptySet()): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        obj.entrySet().forEach { (key, value) -> if (key !in skip) add(out, label(key), value) }
        return out
    }

    private fun add(out: MutableList<Pair<String, String>>, label: String, value: JsonElement?) {
        when {
            value == null || value.isJsonNull -> Unit
            value.isJsonPrimitive -> value.asString.trim().takeIf { it.isNotEmpty() }?.let { out += label to it }
            value.isJsonObject -> value.asJsonObject.entrySet().forEach { (k, v) -> add(out, "$label – ${label(k)}", v) }
            value.isJsonArray -> addArray(out, label, value.asJsonArray)
        }
    }

    private fun addArray(out: MutableList<Pair<String, String>>, label: String, array: JsonArray) {
        if (array.size() == 0) return
        if (array.all { it.isJsonPrimitive }) {
            out += label to array.joinToString(", ") { it.asString }
            return
        }
        array.forEachIndexed { i, item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject
            val docType = obj?.get("identification_type")?.takeIf { it.isJsonPrimitive }?.asString
            val docNumber = obj?.get("identification_number")?.takeIf { it.isJsonPrimitive }?.asString
            if (docType != null && docNumber != null && obj.size() == 2) {
                out += docType to docNumber
            } else {
                add(out, "$label ${i + 1}", item)
            }
        }
    }

    /** first_name -> "First name", originSystem -> "Origin system", record_id -> "Record ID". */
    fun label(key: String): String {
        val words = key
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .trim()
            .lowercase()
            .split(Regex("\\s+"))
            .map { if (it == "id") "ID" else it }
        return words.joinToString(" ").replaceFirstChar(Char::uppercase)
    }
}
