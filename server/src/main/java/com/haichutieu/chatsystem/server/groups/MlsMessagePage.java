package com.haichutieu.chatsystem.server.groups;

import java.util.List;

public record MlsMessagePage(List<MlsMessageView> items, String nextCursor) {
}
