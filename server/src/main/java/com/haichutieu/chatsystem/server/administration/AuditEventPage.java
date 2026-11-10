package com.haichutieu.chatsystem.server.administration;

import java.util.List;

public record AuditEventPage(List<AuditEvent> items, String nextCursor) {
}
