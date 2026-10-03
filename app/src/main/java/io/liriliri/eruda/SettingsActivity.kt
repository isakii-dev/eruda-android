package io.liriliri.eruda

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import io.liriliri.eruda.data.DataStore
import io.liriliri.eruda.mcp.McpServerActivity
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var dataStore: DataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        dataStore = DataStore(this)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        setupThemePicker()
        setupDataRows()
        setupAbout()
    }

    private fun setupThemePicker() {
        val group = findViewById<RadioGroup>(R.id.rgTheme)
        when (prefs.getString(KEY_THEME, THEME_SYSTEM)) {
            THEME_LIGHT -> group.check(R.id.rbLight)
            THEME_DARK -> group.check(R.id.rbDark)
            else -> group.check(R.id.rbSystem)
        }

        group.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.rbLight -> THEME_LIGHT
                R.id.rbDark -> THEME_DARK
                else -> THEME_SYSTEM
            }
            prefs.edit().putString(KEY_THEME, theme).apply()
            AppCompatDelegate.setDefaultNightMode(
                when (theme) {
                    THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }

    private fun setupDataRows() {
        findViewById<View>(R.id.rowClearHistory).setOnClickListener {
            confirmClear(R.string.confirm_clear_history, R.string.confirm_clear_history_sub) {
                dataStore.clearHistory()
            }
        }
        findViewById<View>(R.id.rowClearFavorites).setOnClickListener {
            confirmClear(R.string.confirm_clear_favorites, R.string.confirm_clear_favorites_sub) {
                dataStore.clearBookmarks()
            }
        }
        findViewById<View>(R.id.rowClearCache).setOnClickListener {
            confirmClear(R.string.confirm_clear_cache, R.string.confirm_clear_cache_sub) {
                clearAllCache()
            }
        }
        findViewById<View>(R.id.rowMcpServer).setOnClickListener {
            startActivity(McpServerActivity.intent(this))
        }
    }

    private fun clearAllCache() {
        val tempWebView = WebView(this)
        tempWebView.clearCache(true)
        tempWebView.destroy()

        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()

        WebStorage.getInstance().deleteAllData()
        WebViewDatabase.getInstance(this).clearFormData()

        deleteRecursively(cacheDir)
        deleteRecursively(externalCacheDir)
        deleteRecursively(codeCacheDir)

        prefs.edit().putBoolean(KEY_PENDING_RELOAD, true).apply()
    }

    private fun deleteRecursively(file: File?) {
        if (file == null) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }

    private fun confirmClear(titleRes: Int, messageRes: Int, onConfirm: () -> Unit) {
        showConfirmDialog(this, titleRes, messageRes) {
            onConfirm()
            Toast.makeText(this, R.string.toast_cleared, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        findViewById<TextView>(R.id.versionValue).text = version
    }

    companion object {
        internal const val PREFS_SETTINGS = "eruda_settings"
        private const val KEY_THEME = "theme"
        internal const val KEY_PENDING_RELOAD = "pending_reload"
        private const val THEME_SYSTEM = "system"
        private const val THEME_LIGHT = "light"
        private const val THEME_DARK = "dark"

        /** Aplica o tema salvo. Chamado na inicialização do app. */
        fun applySavedTheme(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            val mode = when (prefs.getString(KEY_THEME, THEME_SYSTEM)) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        fun intent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }
}
