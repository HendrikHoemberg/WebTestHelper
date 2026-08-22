package dev.hendrikhoemberg.webtesthelper.model;

import java.util.List;
import java.util.Objects;

/**
 * A transient result emitted by a check (spec 7.3). The persistent {@code Finding} entity of
 * spec 6.2 is created from it during materialisation, which is the only point where
 * fingerprints and site-wide promotion can be computed — so a check needs no knowledge of
 * identity or lifecycle, and cannot accidentally acquire any.
 *
 * @param subjectKey  the broken thing, already normalised. For a URL subject this is
 *                    {@link NormalizedUrl#value()}, which spec 6.2's normalisation rules are
 *                    already built into.
 * @param observedOn  the page it was seen on; null for a finding about the site as a whole.
 * @param messageArgs arguments for {@code messageKey}, in order. Plain strings: the renderer
 *                    formats, the check does not.
 */
public record CheckFinding(CheckType type, Severity severity, String subjectKey,
                           NormalizedUrl observedOn, String messageKey, List<String> messageArgs,
                           Evidence evidence) {

    public CheckFinding {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(subjectKey, "subjectKey");
        Objects.requireNonNull(messageKey, "messageKey");
        messageArgs = messageArgs == null ? List.of() : List.copyOf(messageArgs);
        evidence = evidence == null ? Evidence.NONE : evidence;
    }

    /**
     * Where it was found, as spec 6.2's {@code locationKey}: the page's path plus surviving
     * query. Materialisation may still promote this to {@code "*"} when the subject turns out
     * to be site-wide, which is knowledge no single check can have.
     */
    public String locationKey() {
        return observedOn == null ? "*" : observedOn.locationKey();
    }
}