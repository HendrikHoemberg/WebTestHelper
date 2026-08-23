package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.Severity;

import java.util.List;

/**
 * One place a {@link MaterialisedFinding} was observed. A page-scoped occurrence carries the
 * page's canonical URL; a site-scoped occurrence has a null {@code pageUrl}.
 */
public record FindingOccurrence(String pageUrl, Severity severity, String messageKey,
                                List<String> messageArgs, Evidence evidence) {

    public FindingOccurrence {
        messageArgs = messageArgs == null ? List.of() : List.copyOf(messageArgs);
        evidence = evidence == null ? Evidence.NONE : evidence;
    }
}
