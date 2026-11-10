package com.haichutieu.chatsystem.server.administration;

import java.util.List;

public record MonthlyCounts(int year, List<MonthlyCount> months) {
}
