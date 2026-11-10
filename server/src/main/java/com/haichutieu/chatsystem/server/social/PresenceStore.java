package com.haichutieu.chatsystem.server.social;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/** Tracks live connections per user so presence survives across server instances. */
public interface PresenceStore {
    void online(UUID userId, String connectionId);

    void offline(UUID userId, String connectionId);

    boolean isOnline(UUID userId);

    Set<UUID> onlineAmong(Collection<UUID> userIds);
}
