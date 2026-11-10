package com.haichutieu.chatsystem.server.groups;

import com.haichutieu.chatsystem.server.messaging.ConversationPage;
import com.haichutieu.chatsystem.server.messaging.ConversationView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupServiceTest {

    @Test
    void createRequiresAtLeastOneDistinctInviteeAndTrimsName() {
        GroupRepository repository = mock(GroupRepository.class);
        GroupService service = new GroupService(repository);
        UUID creator = UUID.randomUUID();
        UUID invited = UUID.randomUUID();
        ConversationView created = new ConversationView(UUID.randomUUID(), "Team", "group", null, List.of());
        when(repository.create(creator, "Team", List.of(invited))).thenReturn(created);

        assertThatThrownBy(() -> service.create(creator, " Team ", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(creator, "Team", List.of(invited, invited)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(creator, "Team", java.util.Arrays.asList(invited, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(creator, "Team", List.of(creator)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.create(creator, " Team ", List.of(invited))).isEqualTo(created);
        verify(repository).create(creator, "Team", List.of(invited));
    }

    @Test
    void listCapsPageSize() {
        GroupRepository repository = mock(GroupRepository.class);
        GroupService service = new GroupService(repository);
        UUID user = UUID.randomUUID();
        ConversationPage page = new ConversationPage(List.of(), null);
        when(repository.list(user, null, 100)).thenReturn(page);

        assertThat(service.list(user, null, 500)).isSameAs(page);
        verify(repository).list(user, null, 100);
    }
}
