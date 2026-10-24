package de.regepower.linksieve

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.Toast

/**
 * Receives links (http, https, market, intent). Applies a remembered rule or asks the user:
 * open the real target app, or block (finish without starting anything).
 */
class ProbeActivity : Activity() {

    private var decided = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming: Intent = intent
        val key = Rules.key(incoming.data)
        when (Rules.get(this, key)) {
            Rules.ALLOW -> finishWith(incoming, key, "allow (rule)", open = true)
            Rules.BLOCK -> {
                Toast.makeText(this, getString(R.string.blocked, key), Toast.LENGTH_SHORT).show()
                finishWith(incoming, key, "block (rule)", open = false)
            }
            else -> ask(incoming, key)
        }
    }

    private fun sourceLabel(): String {
        val pkg = referrer?.takeIf { it.scheme == "android-app" }?.host ?: callingPackage
            ?: return getString(R.string.unknown_source)
        return runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() + " ($pkg)"
        }.getOrDefault(pkg)
    }

    private fun ask(incoming: Intent, key: String) {
        val remember = CheckBox(this).apply { text = getString(R.string.remember, key) }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, 0, pad, 0)
            addView(remember)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.ask_title)
            .setMessage(getString(R.string.ask_message, sourceLabel(), incoming.dataString.orEmpty()))
            .setView(box)
            .setPositiveButton(R.string.open) { _, _ ->
                decided = true
                if (remember.isChecked) Rules.set(this, key, Rules.ALLOW)
                finishWith(incoming, key, "allow", open = true)
            }
            .setNegativeButton(R.string.block) { _, _ ->
                decided = true
                if (remember.isChecked) Rules.set(this, key, Rules.BLOCK)
                finishWith(incoming, key, "block", open = false)
            }
            .setOnDismissListener {
                if (!decided) finishWith(incoming, key, "block (dismissed)", open = false)
            }
            .show()
    }

    private fun finishWith(incoming: Intent, key: String, decision: String, open: Boolean) {
        decided = true
        var result = decision
        if (open && !forward(incoming)) {
            result = "$decision, no target"
            Toast.makeText(this, R.string.no_target, Toast.LENGTH_SHORT).show()
        }
        LogStore.add(
            this,
            LinkEntry(
                time = System.currentTimeMillis(),
                action = incoming.action.orEmpty(),
                data = incoming.dataString.orEmpty(),
                pkg = incoming.`package`.orEmpty(),
                referrer = referrer?.toString().orEmpty(),
                extraReferrer = incoming.getStringExtra(Intent.EXTRA_REFERRER_NAME).orEmpty(),
                callingPackage = callingPackage.orEmpty(),
                ruleKey = key,
                decision = result,
            ),
        )
        finish()
    }

    /** Starts the real handler of the link, never LinkSieve itself. */
    private fun forward(incoming: Intent): Boolean {
        val target: Intent = if (incoming.scheme == "intent") {
            runCatching { Intent.parseUri(incoming.dataString, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return false
        } else {
            Intent(Intent.ACTION_VIEW, incoming.data)
        }
        // Untrusted input: never follow an explicit component or selector from the link.
        target.component = null
        target.selector = null
        target.addCategory(Intent.CATEGORY_BROWSABLE)
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val handlers = query(target).filter { it.activityInfo.packageName != packageName }
        if (handlers.isEmpty()) return false
        // Prefer apps that register this host/scheme specifically over generic browsers.
        val specific = handlers.filter { (it.filter?.countDataAuthorities() ?: 0) > 0 }
        val pick = when {
            specific.size == 1 -> specific[0]
            specific.isEmpty() && handlers.size == 1 -> handlers[0]
            else -> null
        }
        return runCatching {
            if (pick != null) {
                target.setClassName(pick.activityInfo.packageName, pick.activityInfo.name)
                // Already decided here: the app-start guard must not ask again.
                Guard.allowOnce(pick.activityInfo.packageName)
                startActivity(target)
            } else {
                val chooser = Intent.createChooser(target, null)
                    .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, ProbeActivity::class.java)))
                startActivity(chooser)
            }
        }.isSuccess
    }

    private fun query(intent: Intent): List<ResolveInfo> {
        val flags = PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER
        return if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, flags)
        }
    }
}
