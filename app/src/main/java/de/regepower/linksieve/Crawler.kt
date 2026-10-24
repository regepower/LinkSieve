package de.regepower.linksieve

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import android.os.Build
import org.xmlpull.v1.XmlPullParser

/** One link pattern an installed app registers (VIEW intent filter with a data scheme). */
data class LinkFilter(
    val pkg: String,
    val label: String,
    val activity: String,
    val scheme: String,
    val host: String,
    val path: String,
    val autoVerify: Boolean,
) {
    fun pattern(): String = "$scheme://${host.ifEmpty { "*" }}$path"
}

/**
 * Reads the binary AndroidManifest.xml of every installed app and collects its VIEW intent
 * filters. Android has no public API for another app's full filter list.
 */
object Crawler {

    private val SKIP_SCHEMES = setOf("content", "file")

    fun scan(c: Context): List<LinkFilter> {
        val pm = c.packageManager
        val out = ArrayList<LinkFilter>()
        for (ai in installed(pm)) {
            if (ai.packageName == c.packageName) continue
            val label = pm.getApplicationLabel(ai).toString()
            try {
                val parser = pm.getResourcesForApplication(ai).assets.openXmlResourceParser("AndroidManifest.xml")
                parser.use { out += parse(it, ai.packageName, label) }
            } catch (_: Exception) {
                // Unreadable manifest (rare): skip the app.
            }
        }
        return out.distinct().sortedWith(compareBy({ it.label.lowercase() }, { it.scheme }, { it.host }))
    }

    private fun installed(pm: PackageManager): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }

    /** Some apps strip attribute names from the binary manifest, so also match by resource id. */
    private val ATTR_IDS = mapOf(
        "name" to android.R.attr.name,
        "scheme" to android.R.attr.scheme,
        "host" to android.R.attr.host,
        "path" to android.R.attr.path,
        "pathPrefix" to android.R.attr.pathPrefix,
        "pathPattern" to android.R.attr.pathPattern,
        "autoVerify" to android.R.attr.autoVerify,
    )

    private fun index(p: XmlResourceParser, name: String): Int {
        val id = ATTR_IDS[name] ?: 0
        for (i in 0 until p.attributeCount) {
            if (p.getAttributeName(i) == name || (id != 0 && p.getAttributeNameResource(i) == id)) return i
        }
        return -1
    }

    private fun attr(p: XmlResourceParser, name: String): String? =
        index(p, name).takeIf { it >= 0 }?.let { p.getAttributeValue(it) }

    private fun parse(p: XmlResourceParser, pkg: String, label: String): List<LinkFilter> {
        val out = ArrayList<LinkFilter>()
        var activity = ""
        var inFilter = false
        var autoVerify = false
        val actions = HashSet<String>()
        val schemes = LinkedHashSet<String>()
        val hosts = LinkedHashSet<String>()
        val paths = LinkedHashSet<String>()

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (p.name) {
                    "activity", "activity-alias" -> activity = attr(p, "name").orEmpty()
                    "intent-filter" -> {
                        inFilter = true
                        autoVerify = index(p, "autoVerify").let { it >= 0 && p.getAttributeBooleanValue(it, false) }
                        actions.clear(); schemes.clear(); hosts.clear(); paths.clear()
                    }
                    "action" -> if (inFilter) attr(p, "name")?.let { actions += it }
                    "data" -> if (inFilter) {
                        attr(p, "scheme")?.let { schemes += it.lowercase() }
                        attr(p, "host")?.let { hosts += it }
                        (attr(p, "path") ?: attr(p, "pathPrefix")?.let { "$it*" } ?: attr(p, "pathPattern"))
                            ?.let { paths += it }
                    }
                }
            } else if (event == XmlPullParser.END_TAG && p.name == "intent-filter") {
                inFilter = false
                if ("android.intent.action.VIEW" in actions) {
                    val cls = if (activity.startsWith(".")) pkg + activity else activity
                    val path = if (paths.size == 1) paths.first() else ""
                    for (s in schemes) {
                        if (s in SKIP_SCHEMES) continue
                        for (h in hosts.ifEmpty { setOf("") }) {
                            out += LinkFilter(pkg, label, cls, s, h, path, autoVerify)
                        }
                    }
                }
            }
            event = p.next()
        }
        return out
    }
}
