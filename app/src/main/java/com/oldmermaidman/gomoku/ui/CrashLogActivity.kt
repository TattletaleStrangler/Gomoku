package com.oldmermaidman.gomoku.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.oldmermaidman.gomoku.R
import com.oldmermaidman.gomoku.crash.CrashStore
import com.oldmermaidman.gomoku.crash.CrashUploader
import com.oldmermaidman.gomoku.databinding.ActivityCrashLogBinding
import com.oldmermaidman.gomoku.prefs.AppSettings

/**
 * The local crash journal: what the app saved for itself when it crashed, on this
 * device only.
 *
 * There is no automatic upload — the app has no network permission at all — so a
 * report leaves the device only when the player taps "Send" and picks an app in the
 * system share sheet (see README.md, "Crash journal").
 */
class CrashLogActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, CrashLogActivity::class.java)
    }

    private lateinit var binding: ActivityCrashLogBinding
    private lateinit var store: CrashStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCrashLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = CrashStore.inFilesDir(filesDir)
        val theme = Themes.byId(AppSettings(this).theme)

        ThemeSupport.applySystemBarInsets(binding.root)
        ThemeSupport.applyWindow(this, binding.root, theme)
        // Runs before the text is filled in, so the report keeps its own colour.
        ThemeSupport.tintHierarchy(binding.root, theme)
        binding.tvCrashText.setTextColor(theme.statusText)
        binding.tvCrashSummary.setTextColor(theme.accent)

        binding.btnCrashSend.setOnClickListener { send() }
        binding.btnCrashClear.setOnClickListener { confirmClear() }
        binding.btnCrashClose.setOnClickListener { finish() }

        val settings = AppSettings(this)
        binding.swCrashUpload.isChecked = settings.sendCrashReports
        binding.swCrashUpload.setOnCheckedChangeListener { _, checked ->
            settings.sendCrashReports = checked
            // Turning it on sends the reports already on the device right away.
            if (checked) CrashUploader.sendPending(this) { refresh() }
        }

        refresh()
    }

    private fun refresh() {
        val reports = store.list()

        binding.tvCrashSummary.text = if (reports.isEmpty()) {
            getString(R.string.crash_log_empty)
        } else {
            getString(R.string.crash_log_summary, reports.size)
        }
        binding.tvCrashText.text = if (reports.isEmpty()) {
            getString(R.string.crash_log_hint)
        } else {
            store.readAll()
        }

        binding.btnCrashSend.isEnabled = reports.isNotEmpty()
        binding.btnCrashClear.isEnabled = reports.isNotEmpty()
    }

    /** Hands the journal to whatever the player picks in the share sheet. */
    private fun send() {
        val text = store.readAll()
        if (text.isEmpty()) return

        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_log_subject))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(share, getString(R.string.crash_log_send)))
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_log_clear_title)
            .setMessage(R.string.crash_log_clear_message)
            .setPositiveButton(R.string.clear) { _, _ ->
                store.clear()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
