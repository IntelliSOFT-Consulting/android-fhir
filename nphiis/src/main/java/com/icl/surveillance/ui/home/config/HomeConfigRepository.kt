package com.icl.surveillance.ui.home.config

import android.content.Context
import org.json.JSONObject

data class ConfigAction(
	val type: String,
	val route: String? = null,
	val url: String? = null,
	val args: Map<String, String> = emptyMap()
)

data class ConfigItem(
	val id: String,
	val label: String,
	val iconName: String?,
	val action: ConfigAction
)

class HomeConfigRepository(private val context: Context) {

	fun loadItems(): List<ConfigItem> {
		return try {
			val json = context.assets.open("home.json").bufferedReader().use { it.readText() }
			parseItems(JSONObject(json))
		} catch (_: Exception) {
			emptyList()
		}
	}

	private fun parseItems(root: JSONObject): List<ConfigItem> {
		val items = mutableListOf<ConfigItem>()
		val home = root.optJSONObject("home") ?: return emptyList()
		val sections = home.optJSONArray("sections") ?: return emptyList()
		for (i in 0 until sections.length()) {
			val section = sections.optJSONObject(i) ?: continue
			val sectionItems = section.optJSONArray("items") ?: continue
			for (j in 0 until sectionItems.length()) {
				val obj = sectionItems.optJSONObject(j) ?: continue
				val id = obj.optString("id")
				val label = obj.optString("label")
				val icon = obj.optString("icon", null)
				val actionObj = obj.optJSONObject("action") ?: JSONObject()
				val type = actionObj.optString("type")
				val route = actionObj.optString("route", null)
				val url = actionObj.optString("url", null)
				val args = mutableMapOf<String, String>()
				actionObj.optJSONObject("args")?.let { argsObj ->
					for (key in argsObj.keys()) {
						args[key] = argsObj.optString(key)
					}
				}
				items.add(
					ConfigItem(
						id = id,
						label = label,
						iconName = icon,
						action = ConfigAction(type = type, route = route, url = url, args = args)
					)
				)
			}
		}
		return items
	}

	fun resolveIconRes(iconName: String?): Int {
		if (iconName.isNullOrBlank()) return 0
		return context.resources.getIdentifier(iconName, "drawable", context.packageName)
	}
}


