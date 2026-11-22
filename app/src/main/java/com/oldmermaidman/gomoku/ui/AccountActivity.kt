package com.oldmermaidman.gomoku.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.oldmermaidman.gomoku.R
import com.oldmermaidman.gomoku.databinding.ActivityAccountBinding
import com.oldmermaidman.gomoku.online.AccountUi
import com.oldmermaidman.gomoku.online.AccountViewModel
import com.oldmermaidman.gomoku.prefs.AppSettings

/**
 * Sign-in, registration and account management (TZ §11.4).
 *
 * The screen is separate from the game for the same reason the settings screen is: a
 * dialog would interrupt play, while a full screen has room for the profile, the record
 * and the destructive actions. The only dialog is the confirmation before deleting an
 * account, which is outside the game.
 *
 * The chosen theme is applied programmatically, like everywhere else in the app.
 */
class AccountActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, AccountActivity::class.java)
    }

    private lateinit var binding: ActivityAccountBinding
    private val viewModel: AccountViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyTheme()

        binding.btnAccountBack.setOnClickListener { finish() }
        binding.btnSignIn.setOnClickListener {
            viewModel.signIn(binding.etNickname.text.toString(), binding.etPassword.text.toString())
        }
        binding.btnRegister.setOnClickListener {
            viewModel.register(binding.etNickname.text.toString(), binding.etPassword.text.toString())
        }
        binding.btnPlay.setOnClickListener {
            startActivity(OnlineActivity.intent(this))
            finish()
        }
        binding.btnSignOut.setOnClickListener { viewModel.signOut() }
        binding.btnDelete.setOnClickListener { confirmDelete() }

        viewModel.state.observe(this) { render(it) }
    }

    override fun onStart() {
        super.onStart()
        // The record changes as games finish; a returning screen re-reads it.
        viewModel.refreshProfile()
    }

    private fun render(ui: AccountUi) {
        val signedIn = ui.signedIn
        binding.groupSignedOut.visibility = if (signedIn) View.GONE else View.VISIBLE
        binding.groupSignedIn.visibility = if (signedIn) View.VISIBLE else View.GONE

        binding.tvAccountError.text = ui.error
        binding.tvAccountError.visibility = if (ui.error == null) View.GONE else View.VISIBLE

        binding.tvSignedInAs.text = getString(R.string.account_signed_in_as, ui.nickname)
        binding.tvAccountStats.text = getString(
            R.string.account_stats,
            ui.stats.wins,
            ui.stats.losses,
            ui.stats.draws,
        )

        val enabled = !ui.busy
        binding.btnSignIn.isEnabled = enabled
        binding.btnRegister.isEnabled = enabled
        binding.btnPlay.isEnabled = enabled
        binding.btnSignOut.isEnabled = enabled
        binding.btnDelete.isEnabled = enabled
    }

    /**
     * Deletes the account only after the password is typed again: the server requires
     * it anyway, and a destructive action deserves one deliberate step.
     */
    private fun confirmDelete() {
        val password = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.account_delete_password)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.account_delete_title)
            .setMessage(R.string.account_delete_message)
            .setView(password)
            .setPositiveButton(R.string.account_delete_confirm) { _, _ ->
                viewModel.deleteAccount(password.text.toString())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyTheme() {
        val theme = Themes.byId(AppSettings(this).theme)
        ThemeSupport.applySystemBarInsets(binding.root)
        ThemeSupport.applyWindow(this, binding.root, theme)
        ThemeSupport.tintHierarchy(binding.root, theme)

        binding.accountTopBar.setBackgroundColor(theme.background)
        binding.tvAccountTitle.setTextColor(theme.statusText)
        binding.tvAccountError.setTextColor(theme.timeLow)
        binding.btnAccountBack.imageTintList = ColorStateList.valueOf(theme.statusText)
    }
}