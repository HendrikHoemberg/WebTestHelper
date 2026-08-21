package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;

import java.time.Instant;

public record RunLease(long runId, long siteId, RunScope scope, RunTrigger trigger,
                       Instant leaseExpiresAt) {
}
