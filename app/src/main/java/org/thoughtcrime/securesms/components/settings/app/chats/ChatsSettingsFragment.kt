package org.thoughtcrime.securesms.components.settings.app.chats

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.signal.core.ui.compose.ComposeFragment
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.Snackbars
import org.signal.core.ui.compose.Texts
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.backup.isIdle
import org.thoughtcrime.securesms.backup.v2.ui.status.BackupCreationProgressRow
import org.thoughtcrime.securesms.components.compose.rememberBiometricsAuthentication
import org.thoughtcrime.securesms.compose.rememberStatusBarColorNestedScrollModifier
import org.thoughtcrime.securesms.keyvalue.protos.LocalBackupCreationProgress
import org.thoughtcrime.securesms.util.navigation.safeNavigate

/**
 * Displays a list of chats settings options to the user, including
 * generating link previews and keeping muted chats archived.
 */
class ChatsSettingsFragment : ComposeFragment() {

  private val viewModel: ChatsSettingsViewModel by viewModels()

  override fun onResume() {
    super.onResume()
    viewModel.refresh()
  }

  @Composable
  override fun FragmentContent() {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val callbacks = remember { Callbacks() }

    ChatsSettingsScreen(
      state = state,
      callbacks = callbacks
    )
  }

  private inner class Callbacks : ChatsSettingsCallbacks {
    override fun onNavigationClick() {
      requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    override fun onGenerateLinkPreviewsChanged(enabled: Boolean) {
      viewModel.setGenerateLinkPreviewsEnabled(enabled)
    }

    override fun onUseAddressBookChanged(enabled: Boolean) {
      viewModel.setUseAddressBook(enabled)
    }

    override fun onKeepMutedChatsArchivedChanged(enabled: Boolean) {
      viewModel.setKeepMutedChatsArchived(enabled)
    }

    override fun onAddAChatFolderClick() {
      findNavController().safeNavigate(R.id.action_chatsSettingsFragment_to_chatFoldersFragment)
    }

    override fun onAddOrEditFoldersClick() {
      findNavController().safeNavigate(R.id.action_chatsSettingsFragment_to_chatFoldersFragment)
    }

    override fun onUseSystemEmojiChanged(enabled: Boolean) {
      viewModel.setUseSystemEmoji(enabled)
    }

    override fun onEnterKeySendsChanged(enabled: Boolean) {
      viewModel.setEnterKeySends(enabled)
    }

    // BACKCHANNEL: blur media until tapped
    override fun onBlurMediaChanged(enabled: Boolean) {
      viewModel.setBlurMedia(enabled)
    }

    // BACKCHANNEL: save received photos and videos to the gallery
    override fun onAutoSaveMediaChanged(enabled: Boolean) {
      viewModel.setAutoSaveMedia(enabled)
    }

    // BACKCHANNEL: system "Open by default" page, where signal.me / signal.group links can be allowed
    override fun onOpenSignalLinksClick() {
      if (Build.VERSION.SDK_INT >= 31) {
        startActivity(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:" + requireContext().packageName)))
      }
    }

    // BACKCHANNEL: one skin tone for every emoji
    override fun onDefaultSkinToneClick(current: Int) {
      MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.ChatsSettingsFragment__default_skin_tone)
        .setSingleChoiceItems(skinToneLabels(requireContext()), current) { dialog, which ->
          viewModel.setDefaultSkinTone(which)
          dialog.dismiss()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    }

    override fun onExportPlaintextChatHistoryClick() {
      viewModel.requestChatExportType()
    }

    override fun onCancelInFlightExport() {
      viewModel.cancelChatExport()
    }

    // region ChatExportCallback

    override fun onConfirmExport(withMedia: Boolean) {
      viewModel.setExportTypeAndGoToSelectFolder(withMedia)
    }

    override fun onFolderSelected(uri: Uri) {
      viewModel.startChatExportToFolder(uri)
    }

    override fun onCancelStartExport() {
      viewModel.clearChatExportFlow()
    }

    override fun onCompletionConfirmed() {
      viewModel.clearChatExportFlow()
    }

    // endregion
  }
}

private interface ChatsSettingsCallbacks : ChatExportCallbacks {
  fun onNavigationClick() = Unit
  fun onGenerateLinkPreviewsChanged(enabled: Boolean) = Unit
  fun onUseAddressBookChanged(enabled: Boolean) = Unit
  fun onKeepMutedChatsArchivedChanged(enabled: Boolean) = Unit
  fun onAddAChatFolderClick() = Unit
  fun onAddOrEditFoldersClick() = Unit
  fun onUseSystemEmojiChanged(enabled: Boolean) = Unit
  fun onEnterKeySendsChanged(enabled: Boolean) = Unit
  fun onDefaultSkinToneClick(current: Int) = Unit
  fun onOpenSignalLinksClick() = Unit
  fun onBlurMediaChanged(enabled: Boolean) = Unit // BACKCHANNEL
  fun onAutoSaveMediaChanged(enabled: Boolean) = Unit // BACKCHANNEL
  fun onExportPlaintextChatHistoryClick() = Unit
  fun onCancelInFlightExport() = Unit

  object Empty : ChatsSettingsCallbacks, ChatExportCallbacks by ChatExportCallbacks.Empty
}

@Composable
private fun ChatsSettingsScreen(
  state: ChatsSettingsState,
  callbacks: ChatsSettingsCallbacks
) {
  val coroutineScope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }
  val authenticationFailedMessage = stringResource(R.string.ChatsSettingsFragment__authentication_failed)
  val plaintextBiometricsAuthentication = rememberBiometricsAuthentication(
    promptTitle = stringResource(R.string.ChatsSettingsFragment__unlock_to_export_chat_history),
    onAuthenticationFailed = {
      coroutineScope.launch {
        snackbarHostState.showSnackbar(authenticationFailedMessage)
      }
    }
  )

  Scaffolds.Settings(
    title = stringResource(R.string.preferences_chats__chats),
    onNavigationClick = callbacks::onNavigationClick,
    navigationIcon = SignalIcons.ArrowStart.imageVector,
    snackbarHost = {
      Snackbars.Host(snackbarHostState)
    }
  ) { paddingValues ->
    LazyColumn(
      modifier = Modifier
        .padding(paddingValues)
        .then(rememberStatusBarColorNestedScrollModifier())
    ) {
      item {
        Rows.ToggleRow(
          text = stringResource(R.string.preferences__generate_link_previews),
          label = stringResource(R.string.preferences__retrieve_link_previews_from_websites_for_messages),
          enabled = state.isRegisteredAndUpToDate(),
          checked = state.generateLinkPreviews,
          onCheckChanged = callbacks::onGenerateLinkPreviewsChanged
        )
      }

      item {
        Rows.ToggleRow(
          text = stringResource(R.string.preferences__pref_use_address_book_photos),
          label = stringResource(R.string.preferences__display_contact_photos_from_your_address_book_if_available),
          enabled = state.isRegisteredAndUpToDate(),
          checked = state.useAddressBook,
          onCheckChanged = callbacks::onUseAddressBookChanged
        )
      }

      item {
        Rows.ToggleRow(
          text = stringResource(R.string.preferences__pref_keep_muted_chats_archived),
          label = stringResource(R.string.preferences__muted_chats_that_are_archived_will_remain_archived),
          enabled = state.isRegisteredAndUpToDate(),
          checked = state.keepMutedChatsArchived,
          onCheckChanged = callbacks::onKeepMutedChatsArchivedChanged
        )
      }

      // BACKCHANNEL: only Signal can verify signal.me / signal.group links for an app, so Android 12+
      // opens them in the browser until the user allows them for Backchannel in system settings.
      if (Build.VERSION.SDK_INT >= 31) {
        item {
          Rows.TextRow(
            text = stringResource(R.string.ChatsSettingsFragment__open_signal_links),
            label = stringResource(R.string.ChatsSettingsFragment__open_signal_links_summary),
            onClick = callbacks::onOpenSignalLinksClick
          )
        }
      }

      // BACKCHANNEL: contacts hidden from Backchannel's contact lists and pickers
      item {
        val context = LocalContext.current
        var hiddenSummary by remember { mutableStateOf(net.mikesimone.backchannel.HiddenContacts.summary(context)) }
        Rows.TextRow(
          text = stringResource(R.string.HiddenContacts__title),
          label = hiddenSummary,
          onClick = { net.mikesimone.backchannel.HiddenContacts.showManager(context) { hiddenSummary = it } }
        )
      }

      // BACKCHANNEL: away message for 1:1 chats
      item {
        val context = LocalContext.current
        var autoReplySummary by remember { mutableStateOf(net.mikesimone.backchannel.AutoReply.summary(context)) }
        Rows.TextRow(
          text = stringResource(R.string.AutoReply__title),
          label = autoReplySummary,
          onClick = { net.mikesimone.backchannel.AutoReply.edit(context) { autoReplySummary = it } }
        )
      }

      item {
        Dividers.Default()
      }

      // BACKCHANNEL: received photos and videos. Each chat can override these in its chat settings.
      item {
        Texts.SectionHeader(stringResource(R.string.ChatsSettingsFragment__received_media))
      }

      item {
        Rows.ToggleRow(
          text = stringResource(R.string.MediaBlur__blur_all),
          label = stringResource(R.string.MediaBlur__blur_all_summary),
          checked = state.blurMedia,
          onCheckChanged = callbacks::onBlurMediaChanged
        )
      }

      item {
        Rows.ToggleRow(
          text = stringResource(R.string.AutoSaveMedia__save_all),
          label = stringResource(R.string.AutoSaveMedia__save_all_summary),
          checked = state.autoSaveMedia,
          onCheckChanged = callbacks::onAutoSaveMediaChanged
        )
      }

      item {
        Dividers.Default()
      }

      item {
        Texts.SectionHeader(stringResource(R.string.ChatsSettingsFragment__chat_folders))
      }

      if (state.folderCount == 1) {
        item {
          Rows.TextRow(
            text = stringResource(R.string.ChatsSettingsFragment__add_chat_folder),
            enabled = state.isRegisteredAndUpToDate(),
            onClick = callbacks::onAddAChatFolderClick
          )
        }
      } else {
        item {
          Rows.TextRow(
            text = stringResource(R.string.ChatsSettingsFragment__add_edit_chat_folder),
            label = pluralStringResource(R.plurals.ChatsSettingsFragment__d_folder, state.folderCount, state.folderCount),
            enabled = state.isRegisteredAndUpToDate(),
            onClick = callbacks::onAddOrEditFoldersClick
          )
        }
      }

      if (state.isPlaintextExportEnabled) {
        item {
          Dividers.Default()
        }

        if (state.plaintextExportProgress.isIdle) {
          item(key = "export_chat_history_row") {
            Rows.TextRow(
              modifier = Modifier.animateItem(),
              text = stringResource(R.string.ChatsSettingsFragment__export_chat_history),
              label = stringResource(R.string.ChatsSettingsFragment__export_chat_history_label),
              onClick = {
                plaintextBiometricsAuthentication.withBiometricsAuthentication {
                  callbacks.onExportPlaintextChatHistoryClick()
                }
              }
            )
          }
        } else {
          item(key = "export_chat_history_progress") {
            BackupCreationProgressRow(
              modifier = Modifier.animateItem(),
              progress = state.plaintextExportProgress,
              isRemote = false,
              onCancel = callbacks::onCancelInFlightExport
            )
          }
        }
      }

      item {
        Dividers.Default()
      }

      item {
        Texts.SectionHeader(stringResource(R.string.ChatsSettingsFragment__keyboard))
      }

      item {
        Rows.ToggleRow(
          text = stringResource(R.string.preferences_advanced__use_system_emoji),
          enabled = state.isRegisteredAndUpToDate(),
          checked = state.useSystemEmoji,
          onCheckChanged = callbacks::onUseSystemEmojiChanged
        )
      }

      item {
        val context = LocalContext.current
        Rows.TextRow(
          text = stringResource(R.string.ChatsSettingsFragment__default_skin_tone),
          label = skinToneLabels(context)[state.defaultSkinTone],
          enabled = state.isRegisteredAndUpToDate(),
          onClick = { callbacks.onDefaultSkinToneClick(state.defaultSkinTone) }
        )
      }

      item {
        Rows.ToggleRow(
          text = stringResource(R.string.ChatsSettingsFragment__send_with_enter),
          enabled = state.isRegisteredAndUpToDate(),
          checked = state.enterKeySends,
          onCheckChanged = callbacks::onEnterKeySendsChanged
        )
      }
    }
  }

  if (state.isPlaintextExportEnabled) {
    ChatExportDialogs(
      state = state.chatExportState,
      callbacks = callbacks
    )
  }
}

@DayNightPreviews
@Composable
private fun ChatsSettingsScreenPreview() {
  Previews.Preview {
    ChatsSettingsScreen(
      state = ChatsSettingsState(
        generateLinkPreviews = true,
        useAddressBook = true,
        keepMutedChatsArchived = true,
        useSystemEmoji = false,
        enterKeySends = false,
        localBackupsEnabled = true,
        folderCount = 1,
        userUnregistered = false,
        clientDeprecated = false,
        isPlaintextExportEnabled = true,
        plaintextExportProgress = LocalBackupCreationProgress(idle = LocalBackupCreationProgress.Idle())
      ),
      callbacks = ChatsSettingsCallbacks.Empty
    )
  }
}

/** BACKCHANNEL: labels for the default skin tone, in [org.thoughtcrime.securesms.components.emoji.SkinTone] order. */
private fun skinToneLabels(context: Context): Array<String> {
  return arrayOf(
    "\uD83D\uDC4B " + context.getString(R.string.ChatsSettingsFragment__skin_tone_default),
    "\uD83D\uDC4B\uD83C\uDFFB " + context.getString(R.string.ChatsSettingsFragment__skin_tone_light),
    "\uD83D\uDC4B\uD83C\uDFFC " + context.getString(R.string.ChatsSettingsFragment__skin_tone_medium_light),
    "\uD83D\uDC4B\uD83C\uDFFD " + context.getString(R.string.ChatsSettingsFragment__skin_tone_medium),
    "\uD83D\uDC4B\uD83C\uDFFE " + context.getString(R.string.ChatsSettingsFragment__skin_tone_medium_dark),
    "\uD83D\uDC4B\uD83C\uDFFF " + context.getString(R.string.ChatsSettingsFragment__skin_tone_dark)
  )
}
