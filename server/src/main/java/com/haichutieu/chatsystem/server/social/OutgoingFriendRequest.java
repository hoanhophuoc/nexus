package com.haichutieu.chatsystem.server.social;

import java.util.UUID;

public record OutgoingFriendRequest(UUID id, SocialUser to) {
}
