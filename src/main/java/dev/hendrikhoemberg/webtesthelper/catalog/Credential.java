package dev.hendrikhoemberg.webtesthelper.catalog;

import java.time.Instant;

public record Credential(
        long id,
        long siteId,
        String name,
        String username,
        Instant updatedAt,
        boolean readable
) {
}
