package de.regepower.linksieve

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Watches which app comes to the front. When a guarded app appears right after another app
 * (not the launcher, not the notification shade), it was started by that app: go back and ask.
 */
class GuardService : AccessibilityService() {

    private var last = ""
    private val launchers = HashSet<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        launchers.clear()
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(home, 0).forEach { launchers += it.activityInfo.packageName }
    }

    private fun userSource(pkg: String) = pkg.isEmpty() || pkg in launchers || pkg == SYSTEM_UI

    private fun ime(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/').orEmpty()

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "android" || pkg == ime() || pkg == last) return
        val source = last
        last = pkg
        if (userSource(pkg) || userSource(source)) return
        if (pkg !in Guard.targets(this) || Guard.allowedNow(pkg)) return

        val key = Guard.key(source, pkg)
        when (Rules.get(this, key)) {
            Rules.ALLOW -> return
            Rules.BLOCK -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                last = source
                Toast.makeText(this, getString(R.string.blocked, label(pkg)), Toast.LENGTH_SHORT).show()
                log(source, pkg, key, "block (rule)")
            }
            else -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                last = source
                startActivity(
                    Intent(this, GuardActivity::class.java)
                        .putExtra(GuardActivity.EXTRA_SOURCE, source)
                        .putExtra(GuardActivity.EXTRA_TARGET, pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    private fun label(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun log(source: String, target: String, key: String, decision: String) {
        LogStore.add(
            this,
            LinkEntry(
                time = System.currentTimeMillis(),
                action = "app start",
                data = "$source → $target",
                pkg = target,
                referrer = "",
                extraReferrer = "",
                callingPackage = source,
                ruleKey = key,
                decision = decision,
            ),
        )
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
    }
}
