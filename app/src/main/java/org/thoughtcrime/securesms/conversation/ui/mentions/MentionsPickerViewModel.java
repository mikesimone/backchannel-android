package org.thoughtcrime.securesms.conversation.ui.mentions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import java.util.stream.Collectors;

import org.thoughtcrime.securesms.conversation.ui.mentions.MentionsPickerRepository.MentionQuery;
import org.thoughtcrime.securesms.recipients.LiveRecipient;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.SingleLiveEvent;
import org.thoughtcrime.securesms.util.adapter.mapping.MappingModel;
import org.thoughtcrime.securesms.util.livedata.LiveDataUtil;

import java.util.List;
import java.util.Objects;

public class MentionsPickerViewModel extends ViewModel {

  private final SingleLiveEvent<Recipient>      selectedRecipient;
  private final LiveData<List<MappingModel<?>>> mentionList;
  private final MutableLiveData<LiveRecipient>  liveRecipient;
  private final MutableLiveData<Query>          liveQuery;
  private final MutableLiveData<Boolean>        isShowing;
  private final MutableLiveData<List<RecipientId>> multiGroups = new MutableLiveData<>();

  MentionsPickerViewModel(@NonNull MentionsPickerRepository mentionsPickerRepository) {
    this.liveRecipient       = new MutableLiveData<>();
    this.liveQuery           = new MutableLiveData<>();
    this.selectedRecipient   = new SingleLiveEvent<>();
    this.isShowing           = new MutableLiveData<>(false);

    LiveData<Recipient>         recipient    = Transformations.switchMap(liveRecipient, LiveRecipient::getLiveData);
    LiveData<List<RecipientId>> fullMembers  = Transformations.distinctUntilChanged(LiveDataUtil.mapAsync(recipient, mentionsPickerRepository::getMembers));

    // BACKCHANNEL: sharing to several chats offers the members of every chosen group
    LiveData<List<RecipientId>> groupsMembers = LiveDataUtil.mapAsync(multiGroups, mentionsPickerRepository::getMembersOfAll);
    MediatorLiveData<List<RecipientId>> members = new MediatorLiveData<>();
    members.addSource(fullMembers, members::setValue);
    members.addSource(groupsMembers, members::setValue);

    LiveData<MentionQuery>      mentionQuery = LiveDataUtil.combineLatest(liveQuery, members, (q, m) -> new MentionQuery(q.query, m));

    this.mentionList = LiveDataUtil.mapAsync(mentionQuery, q -> mentionsPickerRepository.search(q).stream().<MappingModel<?>>map(MentionViewState::new).collect(Collectors.toList()));
  }

  @NonNull LiveData<List<MappingModel<?>>> getMentionList() {
    return mentionList;
  }

  void onSelectionChange(@NonNull Recipient recipient) {
    selectedRecipient.setValue(recipient);
  }

  void setIsShowing(boolean isShowing) {
    if (Objects.equals(this.isShowing.getValue(), isShowing)) {
      return;
    }
    this.isShowing.setValue(isShowing);
  }

  public @NonNull LiveData<Recipient> getSelectedRecipient() {
    return selectedRecipient;
  }

  public @NonNull LiveData<Boolean> isShowing() {
    return isShowing;
  }

  public void onQueryChange(@Nullable String query) {
    liveQuery.setValue(query == null ? Query.NONE : new Query(query));
  }

  public void onRecipientChange(@NonNull Recipient recipient) {
    this.liveRecipient.setValue(recipient.live());
  }

  /** BACKCHANNEL: the chats a share goes to; mentions offer the members of all their groups. */
  public void onRecipientsChange(@NonNull List<RecipientId> recipientIds) {
    this.multiGroups.setValue(recipientIds);
  }

  /**
   * Wraps a nullable query string so it can be properly propagated through
   * {@link LiveDataUtil#combineLatest(LiveData, LiveData, LiveDataUtil.Combine)}.
   */
  private static class Query {
    static final Query NONE = new Query(null);

    @Nullable private final String query;

    Query(@Nullable String query) {
      this.query = query;
    }

    @Override
    public boolean equals(Object object) {
      if (this == object) {
        return true;
      }

      if (object == null || getClass() != object.getClass()) {
        return false;
      }

      Query other = (Query) object;
      return Objects.equals(query, other.query);
    }

    @Override
    public int hashCode() {
      return Objects.hash(query);
    }
  }

  public static final class Factory implements ViewModelProvider.Factory {
    @Override
    public @NonNull <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
      //noinspection ConstantConditions
      return modelClass.cast(new MentionsPickerViewModel(new MentionsPickerRepository()));
    }
  }
}
