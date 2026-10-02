package io.liriliri.eruda.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class DataStore(context: Context) {

    private val prefs = context.getSharedPreferences("eruda_data", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveHistory(items: List<HistoryItem>) {
        val json = gson.toJson(items)
        prefs.edit().putString(KEY_HISTORY, json).apply()
    }

    fun loadHistory(): MutableList<HistoryItem> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return mutableListOf()
        val type = object : TypeToken<List<HistoryItem>>() {}.type
        return try {
            gson.fromJson<List<HistoryItem>>(json, type).toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun addHistory(item: HistoryItem) {
        val list = loadHistory().apply {
            removeAll { it.url == item.url }
            add(0, item)
            if (size > 200) removeAt(size - 1)
        }
        saveHistory(list)
    }

    fun searchHistory(query: String, limit: Int = 10): List<HistoryItem> {
        val q = query.lowercase()
        return loadHistory().filter {
            it.url.lowercase().contains(q) || it.title.lowercase().contains(q)
        }.take(limit)
    }

    fun getRecentHistory(limit: Int = 20): List<HistoryItem> {
        return loadHistory().take(limit)
    }

    fun removeHistory(url: String) {
        val list = loadHistory().apply { removeAll { it.url == url } }
        saveHistory(list)
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    fun saveBookmarks(items: List<Bookmark>) {
        val json = gson.toJson(items)
        prefs.edit().putString(KEY_BOOKMARKS, json).apply()
    }

    fun loadBookmarks(): MutableList<Bookmark> {
        val json = prefs.getString(KEY_BOOKMARKS, null) ?: return mutableListOf()
        val type = object : TypeToken<List<Bookmark>>() {}.type
        return try {
            gson.fromJson<List<Bookmark>>(json, type).toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun addBookmark(item: Bookmark) {
        val list = loadBookmarks().apply {
            removeAll { it.url == item.url }
            add(0, item)
        }
        saveBookmarks(list)
    }

    fun removeBookmark(url: String) {
        val list = loadBookmarks().apply { removeAll { it.url == url } }
        saveBookmarks(list)
    }

    fun getBookmark(url: String): Bookmark? {
        return loadBookmarks().find { it.url == url }
    }

    fun isBookmarked(url: String): Boolean {
        return loadBookmarks().any { it.url == url }
    }

    fun clearBookmarks() {
        prefs.edit().remove(KEY_BOOKMARKS).apply()
    }

    companion object {
        private const val KEY_HISTORY = "history"
        private const val KEY_BOOKMARKS = "bookmarks"
    }
}