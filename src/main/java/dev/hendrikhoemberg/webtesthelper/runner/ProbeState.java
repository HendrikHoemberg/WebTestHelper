package dev.hendrikhoemberg.webtesthelper.runner;

import java.time.Instant;

/**
 * The state of one guided-setup probe for one site. {@code startedAt} anchors the result TTL:
 * a terminal state older than the TTL is swept on the next {@link SetupProbeService#start},
 * and cannot be returned to a colleague who left the wizard open.
 *
 * @param status     one of {@link ProbeStatus}
 * @param startedAt  when the probe was started (or, once terminal, when the proposal was written)
 * @param proposal   the delivered proposal, {@code null} while {@code LAEUFT} or after a failure
 * @param error      the failure message, {@code null} unless {@code FEHLGESCHLAGEN}
 */
public record ProbeState(ProbeStatus status, Instant startedAt, SetupProposal proposal, String error) {
}
