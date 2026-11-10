package com.haichutieu.chatsystem.server.social;

import java.util.List;

public record UserPage<T>(List<T> items, String nextCursor) {
}
