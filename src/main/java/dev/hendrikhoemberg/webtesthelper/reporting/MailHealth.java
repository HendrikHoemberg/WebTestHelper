package dev.hendrikhoemberg.webtesthelper.reporting;

import java.util.Optional;

public record MailHealth(
        int failedCount,
        Optional<String> lastError
) {
    public boolean hasFailures() {
        return failedCount > 0;
    }
}
