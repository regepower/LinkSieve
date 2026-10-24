package de.regepower.linksieve

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/** Three tabs: log, link patterns of installed apps (scan), and the app-start guard. */
class MainActivity : Activity() {

    private var tab = TAB_LOG
    private val scanMode get() = tab == TAB_SCAN
    private var scanning = false
    private var entries: List<LinkEntry> = emptyList()
    private var found: List<LinkFilter> = emptyList()
    private val caught = HashMap<String, Boolean>()
    private var apps: List<Pair<String, String>> = emptyList()

    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var info: TextView
    private lateinit var tabLog: Button
    private lateinit var tabScan: Button
    private lateinit var tabGuard: Button
    private lateinit var action: Button
    private lateinit var extra: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            setPadding(0, 0, 0, 16)
        }
        tabLog = button(R.string.tab_log) { tab = TAB_LOG; refresh() }
        tabScan = button(R.string.tab_scan) { tab = TAB_SCAN; refresh() }
        tabGuard = button(R.string.tab_guard) { tab = TAB_GUARD; loadApps() }
        action = button(R.string.clear) { onAction() }
        extra = button(R.string.default_browser) { onExtra() }
        info = TextView(this).apply { setPadding(0, 16, 0, 16) }

        val list = ListView(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            when (tab) {
                TAB_SCAN -> showFilter(found[pos])
                TAB_GUARD -> { Guard.toggle(this, apps[pos].first); refresh() }
                else -> showEntry(entries[pos])
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 0)
            addView(title)
            addView(row(tabLog, tabScan, tabGuard))
            addView(row(action, extra))
            addView(info)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun button(textRes: Int, onClick: () -> Unit) = Button(this).apply {
        text = getString(textRes)
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun refresh() {
        tabLog.isEnabled = tab != TAB_LOG
        tabScan.isEnabled = tab != TAB_SCAN
        tabGuard.isEnabled = tab != TAB_GUARD
        extra.visibility = if (tab == TAB_GUARD) View.GONE else View.VISIBLE
        adapter.clear()
        if (tab == TAB_GUARD) {
            action.text = getString(R.string.guard_enable)
            action.isEnabled = true
            val on = Guard.isEnabled(this)
            info.text = getString(if (on) R.string.guard_on else R.string.guard_off) + "\n" + getString(R.string.guard_hint)
            val targets = Guard.targets(this)
            apps = apps.sortedWith(compareBy({ it.first !in targets }, { it.second.lowercase() }))
            adapter.addAll(apps.map { (pkg, label) -> (if (pkg in targets) "✓ " else "    ") + "$label\n$pkg" })
        } else if (scanMode) {
            action.text = getString(if (scanning) R.string.scanning else R.string.scan)
            action.isEnabled = !scanning
            extra.text = getString(R.string.copy_schemes)
            info.text = if (found.isEmpty()) getString(R.string.scan_hint)
            else getString(R.string.scan_count, found.size, found.map { it.pkg }.distinct().size)
            adapter.addAll(found.map { "${it.label}\n${it.pattern()}\n${getString(status(it))}" })
        } else {
            entries = LogStore.all(this)
            action.text = getString(R.string.clear)
            action.isEnabled = true
            extra.text = getString(R.string.default_browser)
            info.text = if (entries.isEmpty()) getString(R.string.no_entries) else null
            adapter.addAll(entries.map { it.summary() })
        }
    }

    private fun onAction() {
        if (tab == TAB_GUARD) {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (!scanMode) {
            LogStore.clear(this)
            refresh()
            return
        }
        scanning = true
        refresh()
        Thread {
            val result = Crawler.scan(applicationContext)
            runOnUiThread {
                found = result
                scanning = false
                refresh()
            }
        }.start()
    }

    /** Launchable apps (plus guarded ones) for the guard target list. */
    private fun loadApps() {
        refresh()
        Thread {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val pkgs = pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.toMutableSet()
            pkgs += Guard.targets(this)
            pkgs -= packageName
            val list = pkgs.map { pkg ->
                pkg to runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            }
            runOnUiThread {
                apps = list
                refresh()
            }
        }.start()
    }

    private fun onExtra() {
        if (scanMode) {
            val missing = found.map { it.scheme }.filter { !catches(it) }.distinct().sorted()
            copy(missing.joinToString("\n"))
        } else {
            open(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    private fun status(f: LinkFilter): Int = when {
        f.scheme in setOf("http", "https") && f.autoVerify -> R.string.st_verified
        f.scheme in setOf("http", "https") -> R.string.st_browser
        catches(f.scheme) -> R.string.st_caught
        else -> R.string.st_missing
    }

    /** Whether LinkSieve's own link activity accepts this scheme (read from our manifest). */
    private fun catches(scheme: String): Boolean = caught.getOrPut(scheme) {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://x"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(packageName)
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
    }

    private fun showFilter(f: LinkFilter) {
        val msg = listOf(
            "${getString(R.string.d_app)}: ${f.label}",
            "${getString(R.string.d_package)}: ${f.pkg}",
            "${getString(R.string.d_activity)}: ${f.activity}",
            "${getString(R.string.d_link)}: ${f.pattern()}",
            "autoVerify: ${f.autoVerify}",
            "",
            getString(status(f)),
        ).joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle(f.label)
            .setMessage(msg)
            .setPositiveButton(R.string.copy) { _, _ -> copy(msg) }
            .setNeutralButton(R.string.link_settings) { _, _ -> openLinkSettings(f.pkg) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showEntry(e: LinkEntry) {
        val b = AlertDialog.Builder(this)
            .setTitle(R.string.detail_title)
            .setMessage(e.text())
            .setPositiveButton(R.string.copy) { _, _ -> copy(e.text()) }
            .setNegativeButton(R.string.close, null)
        if (Rules.get(this, e.ruleKey) != null) {
            b.setNeutralButton(R.string.forget_rule) { _, _ ->
                Rules.remove(this, e.ruleKey)
                Toast.makeText(this, R.string.rule_removed, Toast.LENGTH_SHORT).show()
            }
        }
        b.show()
    }

    /** "Open by default" page of an app, where "Open supported links" can be switched off. */
    private fun openLinkSettings(pkg: String) {
        val uri = Uri.parse("package:$pkg")
        val intent = if (Build.VERSION.SDK_INT >= 31) {
            Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, uri)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
        }
        open(intent)
    }

    private fun open(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure { open(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("LinkSieve", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAB_LOG = 0
        const val TAB_SCAN = 1
        const val TAB_GUARD = 2
    }
}
