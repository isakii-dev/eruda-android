package io.liriliri.eruda

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.liriliri.eruda.data.DataStore

class BookmarksActivity : AppCompatActivity() {

    private lateinit var dataStore: DataStore
    private lateinit var listView: ListView
    private lateinit var btnClear: ImageView
    private var mode: String = MODE_BOOKMARKS
    private var entries: List<Pair<String, String>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bookmarks)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_BOOKMARKS
        dataStore = DataStore(this)

        findViewById<TextView>(R.id.listTitle).setText(
            if (mode == MODE_HISTORY) R.string.history_title else R.string.favorites_title
        )
        findViewById<ImageView>(R.id.emptyIcon).setImageResource(
            if (mode == MODE_HISTORY) R.drawable.ic_history else R.drawable.ic_star
        )
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        listView = findViewById(R.id.bookmarkList)
        btnClear = findViewById(R.id.btnClear)
        listView.setEmptyView(findViewById(android.R.id.empty))

        btnClear.setOnClickListener { confirmClear() }

        bindList()
    }

    private fun bindList() {
        entries = if (mode == MODE_HISTORY) {
            dataStore.getRecentHistory(200).map { it.url to it.title }
        } else {
            dataStore.loadBookmarks().map { it.url to it.title }
        }

        listView.adapter = EntryAdapter()
        btnClear.visibility = if (entries.isEmpty()) View.INVISIBLE else View.VISIBLE

        listView.setOnItemClickListener { _, _, position, _ ->
            val intent = Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_URL, entries[position].first)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            removeEntry(entries[position].first)
            true
        }
    }

    private fun removeEntry(url: String) {
        if (mode == MODE_HISTORY) {
            dataStore.removeHistory(url)
        } else {
            dataStore.removeBookmark(url)
        }
        Toast.makeText(this, R.string.toast_removed, Toast.LENGTH_SHORT).show()
        bindList()
    }

    private fun confirmClear() {
        val message = if (mode == MODE_HISTORY) {
            R.string.confirm_clear_history
        } else {
            R.string.confirm_clear_favorites
        }
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(R.string.action_clear) { _, _ ->
                if (mode == MODE_HISTORY) {
                    dataStore.clearHistory()
                } else {
                    dataStore.clearBookmarks()
                }
                Toast.makeText(this, R.string.toast_cleared, Toast.LENGTH_SHORT).show()
                bindList()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private inner class EntryAdapter : BaseAdapter() {

        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@BookmarksActivity)
                .inflate(R.layout.item_list, parent, false)
            val (url, title) = entries[position]

            view.findViewById<TextView>(R.id.avatar).text = initialOf(url, title)
            view.findViewById<TextView>(R.id.itemTitle).text =
                if (title.isBlank()) url else title
            view.findViewById<TextView>(R.id.itemUrl).text = url
            return view
        }
    }

    private fun initialOf(url: String, title: String): String {
        val source = title.ifBlank {
            url.removePrefix("https://").removePrefix("http://").substringBefore("/")
        }
        return source.trim().firstOrNull()?.uppercase() ?: "?"
    }

    companion object {
        const val MODE_BOOKMARKS = "bookmarks"
        const val MODE_HISTORY = "history"
        private const val EXTRA_MODE = "mode"

        fun intent(context: Context, mode: String): Intent =
            Intent(context, BookmarksActivity::class.java).putExtra(EXTRA_MODE, mode)
    }
}
