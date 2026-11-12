package com.nexus.server.groups;

import java.util.List;

public record MlsMessagePage(List<MlsMessageView> items, String nextCursor) {
}
