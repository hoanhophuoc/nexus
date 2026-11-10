package com.haichutieu.chatsystem.server.administration;

import java.util.List;

public record AdminUserPage(List<AdminUserView> items, String nextCursor) {
}
