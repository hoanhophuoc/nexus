package com.nexus.server.social;

import java.util.UUID;

public record IncomingFriendRequest(UUID id, SocialUser from) {
}
