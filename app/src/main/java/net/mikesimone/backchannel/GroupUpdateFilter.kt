package net.mikesimone.backchannel

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import okio.ByteString
import org.signal.archive.proto.GroupChangeChatUpdate
import org.signal.core.util.Base64
import org.signal.core.util.logging.Log
import org.signal.storageservice.storage.protos.groups.Member
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.databaseprotos.DecryptedGroupV2Context
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import java.util.concurrent.ConcurrentHashMap

/**
 * Hide group updates caused by regular members (Backchannel, Mike 2026-11-11).
 *
 * In a group chat, lines like "X joined via the group link", "X left the group",
 * "X changed their profile name" or "X changed the group photo" are hidden when
 * the person who caused them is not a group admin right now. Lines caused by an
 * admin stay, and so do your own, join requests (an admin may need to act on
 * them), anything whose author can't be told, group calls and safety-number lines.
 *
 * Why: in big groups the member churn drowns the conversation.
 *
 * Off by default. One switch in Settings > Chats; each group can say Default,
 * Hide or Show in its chat settings (stored on this phone only). Display only:
 * nothing is deleted, and turning it off brings every line back.
 *
 * Admins come from the group's current member roles, cached per group whenever
 * the conversation pages in ([prime], on a worker thread), so the bind-time
 * check ([shouldHide]) never touches the database.
 */
object GroupUpdateFilter {

  private val TAG = Log.tag(GroupUpdateFilter::class.java)

  private const val PREFS = "backchannel_group_update_filter"
  private const val KEY_ALL = "hide_all"
  private const val KEY_HIDE = "hide_recipients"
  private const val KEY_SHOW = "show_recipients"

  /** A group's own choice. [DEFAULT] follows the Settings > Chats switch. */
  enum class ChatSetting { DEFAULT, HIDE, SHOW }

  private data class Prefs(val all: Boolean, val hide: Set<String>, val show: Set<String>)

  @Volatile
  private var cache: Prefs? = null

  /** Group recipient -> ACI bytes of its current admins. */
  private val admins = ConcurrentHashMap<RecipientId, Set<ByteString>>()

  private fun prefs(context: Context): Prefs {
    return cache ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let {
      Prefs(
        all = it.getBoolean(KEY_ALL, false),
        hide = it.getStringSet(KEY_HIDE, emptySet())?.toSet() ?: emptySet(),
        show = it.getStringSet(KEY_SHOW, emptySet())?.toSet() ?: emptySet()
      )
    }.also { cache = it }
  }

  private fun save(context: Context, prefs: Prefs) {
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putBoolean(KEY_ALL, prefs.all)
      .putStringSet(KEY_HIDE, prefs.hide)
      .putStringSet(KEY_SHOW, prefs.show)
      .apply()
    cache = prefs
  }

  /** The Settings > Chats switch. */
  @JvmStatic
  fun isEnabledForAll(context: Context): Boolean = prefs(context).all

  @JvmStatic
  fun setEnabledForAll(context: Context, enabled: Boolean) {
    save(context, prefs(context).copy(all = enabled))
  }

  @JvmStatic
  fun getChatSetting(context: Context, recipientId: RecipientId): ChatSetting {
    val prefs = prefs(context)
    val id = recipientId.serialize()
    return when {
      prefs.hide.contains(id) -> ChatSetting.HIDE
      prefs.show.contains(id) -> ChatSetting.SHOW
      else -> ChatSetting.DEFAULT
    }
  }

  @JvmStatic
  fun setChatSetting(context: Context, recipientId: RecipientId, setting: ChatSetting) {
    val prefs = prefs(context)
    val id = recipientId.serialize()
    save(
      context,
      prefs.copy(
        hide = if (setting == ChatSetting.HIDE) prefs.hide + id else prefs.hide - id,
        show = if (setting == ChatSetting.SHOW) prefs.show + id else prefs.show - id
      )
    )
  }

  @JvmStatic
  fun isEnabled(context: Context, recipientId: RecipientId): Boolean {
    return when (getChatSetting(context, recipientId)) {
      ChatSetting.HIDE -> true
      ChatSetting.SHOW -> false
      ChatSetting.DEFAULT -> prefs(context).all
    }
  }

  /** The chat settings row's summary. */
  @JvmStatic
  fun chatSettingLabel(context: Context, recipientId: RecipientId): String {
    return when (getChatSetting(context, recipientId)) {
      ChatSetting.HIDE -> context.getString(R.string.GroupUpdateFilter__hide)
      ChatSetting.SHOW -> context.getString(R.string.GroupUpdateFilter__show)
      ChatSetting.DEFAULT -> defaultLabel(context)
    }
  }

  private fun defaultLabel(context: Context): String {
    return context.getString(if (isEnabledForAll(context)) R.string.GroupUpdateFilter__default_hide else R.string.GroupUpdateFilter__default_show)
  }

  /** The chat settings picker: Default / Hide / Show. [onChanged] runs after a choice. */
  @JvmStatic
  fun showChatSettingDialog(context: Context, recipientId: RecipientId, onChanged: () -> Unit) {
    val options = arrayOf(defaultLabel(context), context.getString(R.string.GroupUpdateFilter__hide), context.getString(R.string.GroupUpdateFilter__show))
    val settings = arrayOf(ChatSetting.DEFAULT, ChatSetting.HIDE, ChatSetting.SHOW)
    MaterialAlertDialogBuilder(context)
      .setTitle(R.string.GroupUpdateFilter__chat_title)
      .setSingleChoiceItems(options, settings.indexOf(getChatSetting(context, recipientId))) { dialog, which ->
        setChatSetting(context, recipientId, settings[which])
        dialog.dismiss()
        onChanged()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  /** Loads [threadRecipient]'s current admins into the cache. Worker thread; called when a conversation pages in. */
  @JvmStatic
  fun prime(context: Context, threadRecipient: Recipient) {
    if (!threadRecipient.isPushV2Group || !isEnabled(context, threadRecipient.id)) return
    try {
      val group = SignalDatabase.groups.getGroup(threadRecipient.id).orElse(null) ?: return
      if (!group.isV2Group) return
      admins[threadRecipient.id] = group.requireV2GroupProperties().decryptedGroup.members
        .filter { it.role == Member.Role.ADMINISTRATOR }
        .map { it.aciBytes }
        .toSet()
    } catch (t: Throwable) {
      Log.w(TAG, "Couldn't read the group's admins", t)
    }
  }

  /** Whether [record] should be hidden in [conversationRecipient]'s chat. Cheap; safe on the main thread. Never throws. */
  @JvmStatic
  fun shouldHide(context: Context, record: MessageRecord, conversationRecipient: Recipient): Boolean {
    return try {
      if (!conversationRecipient.isGroup || !isEnabled(context, conversationRecipient.id)) return false
      val groupAdmins = admins[conversationRecipient.id] ?: return false
      val causers = causers(record) ?: return false
      val self = SignalStore.account.aci?.toByteString()
      if (causers.isEmpty() || causers.any { it == self }) return false
      causers.none { groupAdmins.contains(it) }
    } catch (t: Throwable) {
      Log.w(TAG, "Couldn't check a group update", t)
      false
    }
  }

  /**
   * The ACIs of whoever caused [record], or null when it is not a hideable line or the
   * author can't be told (then it is shown).
   */
  private fun causers(record: MessageRecord): Set<ByteString>? {
    if (record.isProfileChange) {
      return record.fromRecipient.aci.orElse(null)?.toByteString()?.let { setOf(it) }
    }

    if (!record.isGroupUpdate || !record.isGroupV2) return null

    val description = record.messageExtras?.gv2UpdateDescription
    val updates = description?.groupChangeUpdate?.updates
    if (!updates.isNullOrEmpty()) {
      val result = HashSet<ByteString>()
      for (update in updates) {
        val causer = causerOf(update) ?: return null
        if (causer.size == 0) return null
        result += causer
      }
      return result
    }

    val context = description?.gv2ChangeDescription ?: legacyContext(record) ?: return null
    val change = context.change ?: return null
    if (change.newRequestingMembers.isNotEmpty()) return null
    val editor = change.editorServiceIdBytes
    return if (editor.size == 0) null else setOf(editor)
  }

  private fun legacyContext(record: MessageRecord): DecryptedGroupV2Context? {
    val body = record.body
    if (body.isNullOrEmpty()) return null
    return try {
      DecryptedGroupV2Context.ADAPTER.decode(Base64.decode(body))
    } catch (e: Exception) {
      null
    }
  }

  /** Who caused one update, or null to always show it. */
  private fun causerOf(update: GroupChangeChatUpdate.Update): ByteString? {
    return update.genericGroupUpdate?.updaterAci
      ?: update.groupCreationUpdate?.updaterAci
      ?: update.groupNameUpdate?.updaterAci
      ?: update.groupAvatarUpdate?.updaterAci
      ?: update.groupDescriptionUpdate?.updaterAci
      ?: update.groupMembershipAccessLevelChangeUpdate?.updaterAci
      ?: update.groupAttributesAccessLevelChangeUpdate?.updaterAci
      ?: update.groupAnnouncementOnlyChangeUpdate?.updaterAci
      ?: update.groupAdminStatusUpdate?.updaterAci
      ?: update.groupMemberLeftUpdate?.aci
      ?: update.groupMemberRemovedUpdate?.removerAci
      ?: update.groupInvitationAcceptedUpdate?.newMemberAci
      ?: update.groupInvitationDeclinedUpdate?.inviteeAci
      ?: update.groupMemberJoinedUpdate?.newMemberAci
      ?: update.groupMemberAddedUpdate?.updaterAci
      ?: update.groupInvitationRevokedUpdate?.updaterAci
      ?: update.groupJoinRequestApprovalUpdate?.updaterAci
      ?: update.groupJoinRequestCanceledUpdate?.requestorAci
      ?: update.groupInviteLinkResetUpdate?.updaterAci
      ?: update.groupInviteLinkEnabledUpdate?.updaterAci
      ?: update.groupInviteLinkAdminApprovalUpdate?.updaterAci
      ?: update.groupInviteLinkDisabledUpdate?.updaterAci
      ?: update.groupMemberJoinedByLinkUpdate?.newMemberAci
      ?: update.groupSequenceOfRequestsAndCancelsUpdate?.requestorAci
      ?: update.groupExpirationTimerUpdate?.updaterAci
      ?: update.groupMemberLabelAccessLevelChangeUpdate?.updaterAci
      // Everything else (join requests, invites to you, migrations, unknown kinds) is always shown.
  }
}
