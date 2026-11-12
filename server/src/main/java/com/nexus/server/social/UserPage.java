package com.nexus.server.social;

import java.util.List;

public record UserPage<T>(List<T> items, String nextCursor) {
}
