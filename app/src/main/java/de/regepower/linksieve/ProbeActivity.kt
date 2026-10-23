package de.regepower.linksieve

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/** Receives links (http, https, market, intent). Logs them and closes without showing UI. */
class ProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming: Intent = intent
        val entry = LinkEntry(
            time = System.currentTimeMillis(),
            action = incoming.action.orEmpty(),
            data = incoming.dataString.orEmpty(),
            pkg = incoming.`package`.orEmpty(),
            referrer = referrer?.toString().orEmpty(),
            extraReferrer = incoming.getStringExtra(Intent.EXTRA_REFERRER_NAME).orEmpty(),
            callingPackage = callingPackage.orEmpty(),
        )
        LogStore.add(this, entry)
        Log.i(TAG, entry.text())
        Toast.makeText(this, R.string.logged, Toast.LENGTH_SHORT).show()
        finish()
    }

    private companion object {
        const val TAG = "LinkSieve"
    }
}
