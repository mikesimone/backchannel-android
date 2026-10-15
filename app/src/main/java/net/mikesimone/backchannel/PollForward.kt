package net.mikesimone.backchannel

import androidx.annotation.WorkerThread
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.polls.Poll
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.util.getPoll
import kotlin.time.Duration.Companion.seconds

/**
 * One poll to many chats (Backchannel P4). Signal can't forward a poll;
 * Backchannel sends each chosen chat its own new copy, with the same
 * question, options and multiple-votes setting, and no votes.
 */
object PollForward {

  private val TAG = Log.tag(PollForward::class.java)

  /** The poll to copy when forwarding [record], or null if it isn't a poll. */
  @JvmStatic
  fun pollToForward(record: MessageRecord): Poll? {
    val poll = record.getPoll() ?: return null
    return Poll(
      question = poll.question,
      allowMultipleVotes = poll.allowMultipleVotes,
      pollOptions = poll.pollOptions.map { it.text }
    )
  }

  /** Sends [poll] as a new poll in [recipient]'s chat. Same steps as ConversationRepository.sendPoll. */
  @JvmStatic
  @WorkerThread
  fun send(recipient: Recipient, poll: Poll) {
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
    val message = OutgoingMessage.pollMessage(
      threadRecipient = recipient,
      sentTimeMillis = System.currentTimeMillis(),
      expiresIn = recipient.expiresInSeconds.seconds.inWholeMilliseconds,
      poll = poll.copy(authorId = Recipient.self().id.toLong()),
      question = poll.question
    )

    Log.i(TAG, "Sending forwarded poll to " + recipient.id + ", thread: " + threadId)

    MessageSender.sendPollAction(
      AppDependencies.application,
      message,
      threadId,
      MessageSender.SendType.SIGNAL,
      null,
      null
    )
  }
}
