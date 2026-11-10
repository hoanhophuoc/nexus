package com.haichutieu.chatsystem.server.social;

import java.util.List;

public record FriendRequestPage(List<IncomingFriendRequest> items, String nextCursor) {
}
