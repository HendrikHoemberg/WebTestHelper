package dev.hendrikhoemberg.webtesthelper.model;

import java.util.Set;

/** Human disposition of a finding. The triage axis of spec 6.3. */
public enum TriageStatus {
    UNTRIAGED, ACKNOWLEDGED, MUTED, WONT_FIX;

    public static final Set<TriageStatus> SILENCING = Set.of(MUTED, WONT_FIX);

    public boolean silences() {
        return SILENCING.contains(this);
    }
}
