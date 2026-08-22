package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Severity;

import java.util.Set;

/**
 * What every check of every kind carries, and what the documentation enforcement test of spec
 * 13.7 walks.
 *
 * <p>Deviation D14: the three explanation keys are derived from the check type rather than
 * spelled out, so they cannot drift from the enum, and {@link #messageKeys()} extends the same
 * build-failing gate to the keys a finding actually renders — a finding whose key does not
 * resolve reaches the user as {@code ???finding.X.y???}.
 */
public interface CheckDescriptor {

    CheckType type();

    Severity defaultSeverity();

    /** Every finding message key this check can emit. Convention: {@code finding.TYPE.variant}. */
    Set<String> messageKeys();

    default String titleKey() {
        return "check." + type().name() + ".title";
    }

    default String descriptionKey() {
        return "check." + type().name() + ".description";
    }

    default String remediationKey() {
        return "check." + type().name() + ".remediation";
    }
}