package com.nexus.server.social;

import java.util.List;

public record FriendRequestPage(List<IncomingFriendRequest> items, String nextCursor) {
}
