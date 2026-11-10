package com.haichutieu.chatsystem.server.social;

import java.util.UUID;

public record IncomingFriendRequest(UUID id, SocialUser from) {
}
