package de.regepower.linksieve

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LinkEntry(
    val time: Long,
    val action: String,
    val data: String,
    val pkg: String,
    val referrer: String,
    val extraReferrer: String,
    val callingPackage: String,
) {
    fun summary(): String = "${fmt(time)}  ${data.ifEmpty { action }}"

    fun text(): String = listOf(
        "time: ${fmt(time)}",
        "action: $action",
        "data: $data",
        "package: $pkg",
        "referrer (getReferrer): $referrer",
        "referrer (EXTRA_REFERRER_NAME): $extraReferrer",
        "callingPackage: $callingPackage",
    ).joinToString("\n")

    fun toJson(): JSONObject = JSONObject()
        .put("time", time)
        .put("action", action)
        .put("data", data)
        .put("pkg", pkg)
        .put("referrer", referrer)
        .put("extraReferrer", extraReferrer)
        .put("callingPackage", callingPackage)

    companion object {
        fun fromJson(o: JSONObject): LinkEntry = LinkEntry(
            time = o.optLong("time"),
            action = o.optString("action"),
            data = o.optString("data"),
            pkg = o.optString("pkg"),
            referrer = o.optString("referrer"),
            extraReferrer = o.optString("extraReferrer"),
            callingPackage = o.optString("callingPackage"),
        )
    }
}

private fun fmt(time: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date(time))

/** Keeps the last [MAX] incoming intents in SharedPreferences (JSON array, oldest first). */
object LogStore {
    private const val PREFS = "log"
    private const val KEY = "entries"
    private const val MAX = 200

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(c: Context): JSONArray =
        JSONArray(prefs(c).getString(KEY, "[]") ?: "[]")

    /** Newest first. */
    fun all(c: Context): List<LinkEntry> {
        val arr = load(c)
        return (0 until arr.length()).map { LinkEntry.fromJson(arr.getJSONObject(it)) }.reversed()
    }

    fun add(c: Context, e: LinkEntry) {
        val arr = load(c)
        arr.put(e.toJson())
        val trimmed = JSONArray()
        for (i in maxOf(0, arr.length() - MAX) until arr.length()) trimmed.put(arr.get(i))
        prefs(c).edit().putString(KEY, trimmed.toString()).apply()
    }

    fun clear(c: Context) {
        prefs(c).edit().remove(KEY).apply()
    }
}
