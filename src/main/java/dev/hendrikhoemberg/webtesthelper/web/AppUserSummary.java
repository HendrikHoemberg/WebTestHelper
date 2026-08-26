package dev.hendrikhoemberg.webtesthelper.web;

import java.time.Instant;

public record AppUserSummary(long id, String username, AppRole role, boolean enabled, Instant createdAt) {
}
