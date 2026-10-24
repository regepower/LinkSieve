package de.regepower.linksieve

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Per-target decisions ("allow"/"block") remembered by the user. */
object Rules {
    const val ALLOW = "allow"
    const val BLOCK = "block"

    private fun prefs(c: Context) = c.getSharedPreferences("rules", Context.MODE_PRIVATE)

    /** Rule key: market -> app id, web -> host, intent -> its target, else scheme://host. */
    fun key(uri: Uri?): String {
        if (uri == null) return ""
        return when (val scheme = uri.scheme.orEmpty()) {
            "market" -> "market:" + (runCatching { uri.getQueryParameter("id") }.getOrNull() ?: uri.host.orEmpty())
            "http", "https" -> uri.host.orEmpty()
            "intent" -> {
                val parsed = runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()
                val inner = parsed?.data
                when {
                    inner != null && inner.scheme != "intent" -> key(inner)
                    parsed?.`package` != null -> "app:" + parsed.`package`
                    else -> "intent://" + uri.host.orEmpty()
                }
            }
            else -> "$scheme://${uri.host.orEmpty()}"
        }
    }

    fun get(c: Context, key: String): String? = if (key.isEmpty()) null else prefs(c).getString(key, null)

    fun set(c: Context, key: String, value: String) {
        if (key.isNotEmpty()) prefs(c).edit().putString(key, value).apply()
    }

    fun remove(c: Context, key: String) {
        prefs(c).edit().remove(key).apply()
    }
}
