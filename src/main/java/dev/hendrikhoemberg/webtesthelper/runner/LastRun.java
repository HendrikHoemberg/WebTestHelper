package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.model.RunStatus;

import java.time.Instant;

/**
 * The newest terminal run of a site, one per site (spec 16's "last run" grid column).
 *
 * @param siteId          the owning site
 * @param runId           the run id
 * @param status          {@code COMPLETED} or {@code FAILED} — never {@code QUEUED}/{@code RUNNING}
 * @param finishedAt      when the run finished
 * @param partialCoverage whether the crawl stopped early on the page budget
 */
public record LastRun(long siteId, long runId, RunStatus status, Instant finishedAt,
                      boolean partialCoverage) {
}
