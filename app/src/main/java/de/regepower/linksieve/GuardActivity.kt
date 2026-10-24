package de.regepower.linksieve

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.Toast

/** Dialog after the guard went back: open the target app after all, or keep it blocked. */
class GuardActivity : Activity() {

    private var decided = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
        val target = intent.getStringExtra(EXTRA_TARGET).orEmpty()
        val key = Guard.key(source, target)

        val remember = CheckBox(this).apply { text = getString(R.string.remember_pair) }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, 0, pad, 0)
            addView(remember)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.guard_title)
            .setMessage(getString(R.string.guard_message, label(source), label(target)))
            .setView(box)
            .setPositiveButton(R.string.open) { _, _ ->
                decided = true
                if (remember.isChecked) Rules.set(this, key, Rules.ALLOW)
                log(source, target, key, "allow")
                Guard.allowOnce(target)
                val launch = packageManager.getLaunchIntentForPackage(target)
                if (launch == null || runCatching { startActivity(launch) }.isFailure) {
                    Toast.makeText(this, R.string.no_target, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.block) { _, _ ->
                decided = true
                if (remember.isChecked) Rules.set(this, key, Rules.BLOCK)
                log(source, target, key, "block")
            }
            .setOnDismissListener {
                if (!decided) log(source, target, key, "block (dismissed)")
                finish()
            }
            .show()
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

    companion object {
        const val EXTRA_SOURCE = "source"
        const val EXTRA_TARGET = "target"
    }
}
