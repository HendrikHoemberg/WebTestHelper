package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.crawler.ProbeEvidence;

import java.util.List;

/**
 * What the guided-setup screen confirms instead of authoring by hand: the evidence the probe
 * read plus one {@link CheckProposal} per {@link dev.hendrikhoemberg.webtesthelper.model.CheckType}.
 *
 * @param evidence  what the probe saw; the screen reads its lists directly for the
 *                  information-only lines (a found form, for instance, has no check to tick)
 * @param checks    the full catalog, one entry per check type, where the probe decided only
 *                  {@code suggested} and the reason
 */
public record SetupProposal(ProbeEvidence evidence, List<CheckProposal> checks) {

    public SetupProposal {
        checks = List.copyOf(checks);
    }

    /** The pages carrying a form, as plain URLs. Exposed here so the {@code web} module reads
     *  them off this record instead of reaching into {@link ProbeEvidence} directly — the
     *  {@code web} module may not depend on the {@code crawler} module. */
    public List<String> formPages() {
        return evidence.formPages();
    }
}
