package de.regepower.linksieve

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/** Shows the log of received links. Tap an entry for details, copy them from there. */
class MainActivity : Activity() {

    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var empty: TextView
    private var entries: List<LinkEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            setPadding(0, 0, 0, 24)
        }
        val clear = Button(this).apply {
            text = getString(R.string.clear)
            setOnClickListener {
                LogStore.clear(this@MainActivity)
                reload()
            }
        }
        empty = TextView(this).apply {
            text = getString(R.string.no_entries)
            setPadding(0, 24, 0, 24)
        }
        val list = ListView(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> showDetail(entries[position]) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 0)
            addView(title)
            addView(clear)
            addView(empty)
            addView(list, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        entries = LogStore.all(this)
        adapter.clear()
        adapter.addAll(entries.map { it.summary() })
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showDetail(e: LinkEntry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.detail_title)
            .setMessage(e.text())
            .setPositiveButton(R.string.copy) { _, _ ->
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("LinkSieve", e.text()))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
