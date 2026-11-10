package com.haichutieu.chatsystem.server.administration;

import java.util.List;

public record ReportPage(List<Report> items, String nextCursor) {
}
