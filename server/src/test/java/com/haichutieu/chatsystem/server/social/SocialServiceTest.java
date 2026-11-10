package com.haichutieu.chatsystem.server.social;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SocialServiceTest {

    @Test
    void searchTrimsQueryAndCapsPageSize() {
        CapturingSocialRepository repository = new CapturingSocialRepository();
        SocialService service = new SocialService(repository, mock(PresenceStore.class));
        UUID viewerId = UUID.randomUUID();

        service.search(viewerId, "  alice ", null, 500);

        assertThat(repository.viewerId).isEqualTo(viewerId);
        assertThat(repository.query).isEqualTo("alice");
        assertThat(repository.limit).isEqualTo(100);
    }

    @Test
    void selfRequestsAndBlocksAreRejectedBeforeStorage() {
        CapturingSocialRepository repository = new CapturingSocialRepository();
        SocialService service = new SocialService(repository, mock(PresenceStore.class));
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> service.sendRequest(userId, userId)).isInstanceOf(SocialConflictException.class);
        assertThatThrownBy(() -> service.block(userId, userId)).isInstanceOf(SocialConflictException.class);
        assertThat(repository.writeCalls).isZero();
    }

    @Test
    void messageEvidenceRequiresExplicitConsent() {
        CapturingSocialRepository repository = new CapturingSocialRepository();
        SocialService service = new SocialService(repository, mock(PresenceStore.class));
        ReportSubmission request = new ReportSubmission(UUID.randomUUID(), "spam", false,
                List.of(new ReportEvidenceSubmission(UUID.randomUUID(), "selected message text")));

        assertThatThrownBy(() -> service.submitReport(UUID.randomUUID(), request))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.writeCalls).isZero();
    }

    @Test
    void friendPagesReflectFreshPresence() {
        SocialRepository repository = mock(SocialRepository.class);
        PresenceStore presence = mock(PresenceStore.class);
        UUID viewerId = UUID.randomUUID();
        UUID friendId = UUID.randomUUID();
        SocialUser friend = new SocialUser(friendId, "bob", "Bob", null, null, null, "offline");
        when(repository.friends(viewerId, null, 20))
                .thenReturn(new UserPage<>(List.of(friend), null));
        when(presence.onlineAmong(List.of(friendId))).thenReturn(Set.of(friendId));

        var page = new SocialService(repository, presence).friends(viewerId, null, 20);

        assertThat(page.items()).singleElement().extracting(SocialUser::presence).isEqualTo("online");
    }

    @Test
    void outgoingAndBlockedPagesReflectPresenceAndForwardPaging() {
        SocialRepository repository = mock(SocialRepository.class);
        PresenceStore presence = mock(PresenceStore.class);
        UUID viewerId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        SocialUser other = new SocialUser(otherId, "bob", "Bob", null, null, null, "offline");
        when(repository.outgoingRequests(viewerId, null, 20))
                .thenReturn(new OutgoingFriendRequestPage(
                        List.of(new OutgoingFriendRequest(UUID.randomUUID(), other)), null));
        when(repository.blocked(viewerId, null, 20)).thenReturn(new UserPage<>(List.of(other), null));
        when(presence.onlineAmong(List.of(otherId))).thenReturn(Set.of(otherId));

        SocialService service = new SocialService(repository, presence);

        assertThat(service.outgoingRequests(viewerId, null, 20).items())
                .singleElement().extracting(item -> item.to().presence()).isEqualTo("online");
        assertThat(service.blocked(viewerId, null, 20).items())
                .singleElement().extracting(SocialUser::presence).isEqualTo("online");
    }

    @Test
    void cancellingAnOutgoingRequestDelegatesToStorage() {
        CapturingSocialRepository repository = new CapturingSocialRepository();
        SocialService service = new SocialService(repository, mock(PresenceStore.class));
        UUID requestId = UUID.randomUUID();

        service.cancelRequest(UUID.randomUUID(), requestId);

        assertThat(repository.cancelledRequest).isEqualTo(requestId);
    }

    private static final class CapturingSocialRepository implements SocialRepository {
        private UUID viewerId;
        private String query;
        private int limit;
        private int writeCalls;
        private UUID cancelledRequest;

        @Override public UserPage<SocialUser> search(UUID viewer, String text, UUID cursor, int pageSize) {
            viewerId = viewer;
            query = text;
            limit = pageSize;
            return new UserPage<>(List.of(), null);
        }
        @Override public FriendRequestPage incomingRequests(UUID user, UUID cursor, int pageSize) {
            return new FriendRequestPage(List.of(), null);
        }
        @Override public OutgoingFriendRequestPage outgoingRequests(UUID user, UUID cursor, int pageSize) {
            return new OutgoingFriendRequestPage(List.of(), null);
        }
        @Override public UserPage<SocialUser> friends(UUID user, UUID cursor, int pageSize) {
            return new UserPage<>(List.of(), null);
        }
        @Override public UserPage<SocialUser> blocked(UUID user, UUID cursor, int pageSize) {
            return new UserPage<>(List.of(), null);
        }
        @Override public void sendRequest(UUID requester, UUID target) { writeCalls++; }
        @Override public void decideRequest(UUID user, UUID request, boolean accept) { writeCalls++; }
        @Override public void cancelRequest(UUID user, UUID request) { cancelledRequest = request; }
        @Override public void removeFriend(UUID user, UUID friend) { writeCalls++; }
        @Override public void block(UUID blocker, UUID blocked) { writeCalls++; }
        @Override public void unblock(UUID blocker, UUID blocked) { writeCalls++; }
        @Override public boolean canStartDirectConversation(UUID first, UUID second) { return false; }
        @Override public UUID submitReport(UUID reporter, ReportSubmission submission) { writeCalls++; return UUID.randomUUID(); }
    }
}
