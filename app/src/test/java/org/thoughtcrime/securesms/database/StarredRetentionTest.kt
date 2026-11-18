package org.thoughtcrime.securesms.database

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testutil.RecipientTestRule

/** Backchannel: retention trims never delete a starred message. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class StarredRetentionTest {

  @get:Rule
  val recipients = RecipientTestRule()

  private val messages: MessageTable
    get() = SignalDatabase.messages

  private lateinit var senderId: RecipientId
  private var threadId: Long = 0

  @Before
  fun setUp() {
    senderId = recipients.createRecipient("Sender Name")
    threadId = SignalDatabase.threads.getOrCreateThreadIdFor(senderId, false, ThreadTable.DistributionTypes.DEFAULT)
  }

  @Test
  fun trimByDateKeepsStarredMessages() {
    val old = insertIncoming(1000)
    val oldStarred = insertIncoming(1001)
    val recent = insertIncoming(9000)
    star(oldStarred)

    SignalDatabase.threads.trimThread(threadId = threadId, syncThreadTrimDeletes = false, trimBeforeDate = 5000)

    assertNull(messages.getMessageRecordOrNull(old))
    assertNotNull(messages.getMessageRecordOrNull(oldStarred))
    assertNotNull(messages.getMessageRecordOrNull(recent))
    assertEquals(0, messages.getMessageCountBeforeDate(5000))
  }

  @Test
  fun trimByLengthKeepsStarredMessages() {
    val starred = insertIncoming(1000)
    val dropped = insertIncoming(1001)
    insertIncoming(1002)
    insertIncoming(1003)
    star(starred)

    SignalDatabase.threads.trimThread(threadId = threadId, syncThreadTrimDeletes = false, length = 2)

    assertNotNull(messages.getMessageRecordOrNull(starred))
    assertNull(messages.getMessageRecordOrNull(dropped))
  }

  @Test
  fun trimKeepsEditHistoryOfStarredMessage() {
    val original = insertIncoming(1000)
    val target = messages.getMessageRecord(original) as MmsMessageRecord
    val edit = IncomingMessage(
      type = MessageType.NORMAL,
      from = senderId,
      sentTimeMillis = 1100,
      serverTimeMillis = 1100,
      receivedTimeMillis = 1100,
      body = "edited"
    )
    val latest = messages.insertEditMessageInbox(edit, target).get().messageId
    star(latest)

    SignalDatabase.threads.trimThread(threadId = threadId, syncThreadTrimDeletes = false, trimBeforeDate = 5000)

    assertNotNull(messages.getMessageRecordOrNull(latest))
    assertNotNull(messages.getMessageRecordOrNull(original))
  }

  @Test
  fun unstarredMessageIsTrimmedAgain() {
    val id = insertIncoming(1000)
    star(id)
    SignalDatabase.threads.trimThread(threadId = threadId, syncThreadTrimDeletes = false, trimBeforeDate = 5000)
    assertNotNull(messages.getMessageRecordOrNull(id))

    SignalDatabase.writableDatabase.execSQL("UPDATE ${MessageTable.TABLE_NAME} SET ${MessageTable.STARRED} = 0 WHERE ${MessageTable.ID} = ?", arrayOf(id))
    SignalDatabase.threads.trimThread(threadId = threadId, syncThreadTrimDeletes = false, trimBeforeDate = 5000)
    assertNull(messages.getMessageRecordOrNull(id))
  }

  private fun star(id: Long) {
    SignalDatabase.writableDatabase.execSQL("UPDATE ${MessageTable.TABLE_NAME} SET ${MessageTable.STARRED} = 1 WHERE ${MessageTable.ID} = ?", arrayOf(id))
    assertEquals(true, messages.isStarred(id))
  }

  private fun insertIncoming(time: Long): Long {
    val message = IncomingMessage(
      type = MessageType.NORMAL,
      from = senderId,
      sentTimeMillis = time,
      serverTimeMillis = time,
      receivedTimeMillis = time,
      body = "msg $time"
    )
    return messages.insertMessageInbox(message, threadId).get().messageId
  }
}
