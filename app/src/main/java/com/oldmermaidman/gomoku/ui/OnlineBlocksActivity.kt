package com.oldmermaidman.gomoku.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.oldmermaidman.gomoku.R
import com.oldmermaidman.gomoku.databinding.ActivityOnlineBlocksBinding
import com.oldmermaidman.gomoku.databinding.ItemBlockedPlayerBinding
import com.oldmermaidman.gomoku.online.OnlineBlocksUi
import com.oldmermaidman.gomoku.online.OnlineBlocksViewModel
import com.oldmermaidman.gomoku.prefs.AppSettings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The players this account has blocked (FUTURE.md).
 *
 * "Do not pair me with this player" is one tap away on a finished game, so mistakes are
 * expected: this screen is the way back, with one button per row. A player blocked by
 * somebody else never appears here — the list is the account's own, in one direction.
 */
class OnlineBlocksActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, OnlineBlocksActivity::class.java)

        private val ROW_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }

    private lateinit var binding: ActivityOnlineBlocksBinding
    private val viewModel: OnlineBlocksViewModel by viewModels()
    private var theme: GomokuTheme = Themes.LIGHT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnlineBlocksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyTheme()

        binding.btnBlocksBack.setOnClickListener { finish() }
        viewModel.state.observe(this) { render(it) }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refresh()
    }

    private fun render(ui: OnlineBlocksUi) {
        ui.message?.let { text ->
            viewModel.consumeMessage()
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }

        binding.blocksList.removeAllViews()

        val error = ui.error
        val message = when {
            ui.loading && ui.blocked.isEmpty() -> getString(R.string.online_lobby_loading)
            error != null -> error
            ui.blocked.isEmpty() -> getString(R.string.blocks_empty)
            else -> null
        }
        binding.tvBlocksEmpty.text = message
        binding.tvBlocksEmpty.visibility = if (message == null) View.GONE else View.VISIBLE
        binding.tvBlocksEmpty.setTextColor(if (error != null) theme.timeLow else theme.statusText)

        for (blocked in ui.blocked) {
            val item = ItemBlockedPlayerBinding.inflate(layoutInflater, binding.blocksList, false)
            val nickname = blocked.nickname?.takeIf { it.isNotBlank() }
                ?: getString(R.string.blocks_deleted)
            item.tvBlockedName.text = listOfNotNull(nickname, dateOf(blocked.blockedAt))
                .joinToString(" · ")
            item.tvBlockedName.setTextColor(theme.statusText)
            item.btnUnblock.setOnClickListener {
                viewModel.unblock(blocked.userId, blocked.nickname)
            }
            binding.blocksList.addView(item.root)
        }
    }

    /** "12.11.2026" from the instant the server sent, or null when it cannot be read. */
    private fun dateOf(text: String?): String? = runCatching {
        Instant.parse(text).atZone(ZoneId.systemDefault()).format(ROW_DATE)
    }.getOrNull()

    private fun applyTheme() {
        theme = Themes.byId(AppSettings(this).theme)
        ThemeSupport.applySystemBarInsets(binding.root)
        ThemeSupport.applyWindow(this, binding.root, theme)
        ThemeSupport.tintHierarchy(binding.root, theme)

        binding.blocksTopBar.setBackgroundColor(theme.background)
        binding.tvBlocksTitle.setTextColor(theme.statusText)
        binding.tvBlocksEmpty.setTextColor(theme.statusText)
        binding.btnBlocksBack.imageTintList = ColorStateList.valueOf(theme.statusText)
    }
}