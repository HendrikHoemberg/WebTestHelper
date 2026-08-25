package dev.hendrikhoemberg.webtesthelper.model;

import java.util.Set;

/** Human disposition of a finding. The triage axis of spec 6.3. */
public enum TriageStatus {
    UNTRIAGED, ACKNOWLEDGED, MUTED, WONT_FIX;

    public static final Set<TriageStatus> SILENCING = Set.of(MUTED, WONT_FIX);

    public boolean silences() {
        return SILENCING.contains(this);
    }

    public boolean requiresExpiry() {
        return this == MUTED;
    }

    public boolean requiresReason() {
        return this == MUTED || this == WONT_FIX;
    }

    public boolean allowsReason() {
        return this != UNTRIAGED;
    }

    public static java.util.List<TriageStatus> formActions() {
        return java.util.List.of(ACKNOWLEDGED, MUTED, WONT_FIX, UNTRIAGED);
    }

    public static TriageStatus defaultFormAction() {
        return ACKNOWLEDGED;
    }

    public static String expiryActionNames() {
        return java.util.Arrays.stream(values())
                .filter(TriageStatus::requiresExpiry)
                .map(Enum::name)
                .collect(java.util.stream.Collectors.joining(","));
    }

    public static String mandatoryReasonActionNames() {
        return java.util.Arrays.stream(values())
                .filter(TriageStatus::requiresReason)
                .map(Enum::name)
                .collect(java.util.stream.Collectors.joining(","));
    }

    public static String reasonActionNames() {
        return java.util.Arrays.stream(values())
                .filter(TriageStatus::allowsReason)
                .map(Enum::name)
                .collect(java.util.stream.Collectors.joining(","));
    }
}
