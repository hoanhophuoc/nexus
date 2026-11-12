package com.nexus.server.administration;

import java.util.List;

public record AdminUserPage(List<AdminUserView> items, String nextCursor) {
}
