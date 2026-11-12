package com.nexus.server.administration;

import java.util.List;

public record AuditEventPage(List<AuditEvent> items, String nextCursor) {
}
