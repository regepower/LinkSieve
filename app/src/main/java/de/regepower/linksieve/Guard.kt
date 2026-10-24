package de.regepower.linksieve

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/** Settings and state of the app-start guard (accessibility service). */
object Guard {
    private const val PREFS = "guard"
    private const val KEY_TARGETS = "targets"
    private val DEFAULT_TARGETS = setOf("com.android.vending")

    @Volatile private var allowPkg = ""
    @Volatile private var allowUntil = 0L

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Apps whose start from another app is intercepted. */
    fun targets(c: Context): Set<String> =
        HashSet(prefs(c).getStringSet(KEY_TARGETS, null) ?: DEFAULT_TARGETS)

    fun toggle(c: Context, pkg: String) {
        val set = HashSet(targets(c))
        if (!set.remove(pkg)) set.add(pkg)
        prefs(c).edit().putStringSet(KEY_TARGETS, set).apply()
    }

    /** Rule key for "source app starts target app". */
    fun key(source: String, target: String) = "start:$source>$target"

    /** Let [pkg] come to the front once without asking (after the user chose "Open"). */
    fun allowOnce(pkg: String) {
        allowPkg = pkg
        allowUntil = SystemClock.elapsedRealtime() + 8000
    }

    fun allowedNow(pkg: String) = pkg == allowPkg && SystemClock.elapsedRealtime() < allowUntil

    fun isEnabled(c: Context): Boolean {
        val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(c, GuardService::class.java)
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}
