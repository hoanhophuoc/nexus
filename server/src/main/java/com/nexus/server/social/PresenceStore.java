package com.nexus.server.social;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/** Tracks live connections per user so presence survives across server instances. */
public interface PresenceStore {
    void online(UUID userId, String connectionId);

    void offline(UUID userId, String connectionId);

    /**
     * Refreshes every heartbeat in one call. Stores with a remote backend should override this
     * to batch the work instead of paying a round trip per connection.
     */
    default void refresh(java.util.Map<UUID, ? extends Collection<String>> connections) {
        connections.forEach((userId, ids) -> ids.forEach(id -> online(userId, id)));
    }

    boolean isOnline(UUID userId);

    Set<UUID> onlineAmong(Collection<UUID> userIds);
}
