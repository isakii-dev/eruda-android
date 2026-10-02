package io.liriliri.eruda

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import io.liriliri.eruda.data.DataStore

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
            confirmClear(R.string.confirm_clear_history) {
                dataStore.clearHistory()
            }
        }
        findViewById<View>(R.id.rowClearFavorites).setOnClickListener {
            confirmClear(R.string.confirm_clear_favorites) {
                dataStore.clearBookmarks()
            }
        }
    }

    private fun confirmClear(messageRes: Int, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(messageRes)
            .setPositiveButton(R.string.action_clear) { _, _ ->
                onConfirm()
                Toast.makeText(this, R.string.toast_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
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
        private const val PREFS_SETTINGS = "eruda_settings"
        private const val KEY_THEME = "theme"
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
