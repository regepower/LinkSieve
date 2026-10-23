package de.regepower.linksieve

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView

class ProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming: Intent = intent
        val info = buildString {
            appendLine("action: ${incoming.action}")
            appendLine("data: ${incoming.dataString}")
            appendLine("component: ${incoming.component}")
            appendLine("package: ${incoming.`package`}")
            appendLine("getReferrer(): ${referrer}")
            appendLine("EXTRA_REFERRER_NAME: ${incoming.getStringExtra(Intent.EXTRA_REFERRER_NAME)}")
            appendLine("callingPackage: ${callingPackage}")
            appendLine("callingActivity: ${callingActivity}")
        }
        Log.i(TAG, info)

        // Phone-only testing: show the same info on screen (selectable, scrollable).
        val text = TextView(this).apply {
            text = info
            setTextIsSelectable(true)
            setPadding(48, 48, 48, 48)
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }

    private companion object {
        const val TAG = "LinkSieve"
    }
}
