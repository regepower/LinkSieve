package de.regepower.linksieve

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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

/** Two views: log of received links, and the scan of link patterns registered by installed apps. */
class MainActivity : Activity() {

    private var scanMode = false
    private var scanning = false
    private var entries: List<LinkEntry> = emptyList()
    private var found: List<LinkFilter> = emptyList()

    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var info: TextView
    private lateinit var tabLog: Button
    private lateinit var tabScan: Button
    private lateinit var action: Button
    private lateinit var extra: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            setPadding(0, 0, 0, 16)
        }
        tabLog = button(R.string.tab_log) { scanMode = false; refresh() }
        tabScan = button(R.string.tab_scan) { scanMode = true; refresh() }
        action = button(R.string.clear) { onAction() }
        extra = button(R.string.default_browser) { onExtra() }
        info = TextView(this).apply { setPadding(0, 16, 0, 16) }

        val list = ListView(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            if (scanMode) showFilter(found[pos]) else showEntry(entries[pos])
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 0)
            addView(title)
            addView(row(tabLog, tabScan))
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

    private fun row(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun refresh() {
        tabLog.isEnabled = scanMode
        tabScan.isEnabled = !scanMode
        adapter.clear()
        if (scanMode) {
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

    private fun onExtra() {
        if (scanMode) {
            val missing = found.map { it.scheme }.filter { it !in Rules.SCHEMES }.distinct().sorted()
            copy(missing.joinToString("\n"))
        } else {
            open(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    private fun status(f: LinkFilter): Int = when {
        f.scheme in setOf("http", "https") && f.autoVerify -> R.string.st_verified
        f.scheme in setOf("http", "https") -> R.string.st_browser
        f.scheme in Rules.SCHEMES -> R.string.st_caught
        else -> R.string.st_missing
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
}
