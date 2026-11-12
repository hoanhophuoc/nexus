package com.nexus.server.social;

import java.util.UUID;

public record OutgoingFriendRequest(UUID id, SocialUser to) {
}
