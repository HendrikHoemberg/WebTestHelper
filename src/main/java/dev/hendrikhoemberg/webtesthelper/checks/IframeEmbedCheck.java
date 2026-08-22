package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.ConsoleMessage;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.FailedRequest;
import dev.hendrikhoemberg.webtesthelper.model.FrameRef;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An embedded frame is not blocked, and shows something (spec 7.1).
 *
 * <p>Three signals, applied per frame in the order they can be trusted:
 *
 * <ol>
 *   <li><strong>Blocked.</strong> The snapshot carries a failed {@code document} request for the
 *       frame's URL. Measured against the fixture, an {@code X-Frame-Options: DENY} frame fails
 *       with {@code net::ERR_BLOCKED_BY_RESPONSE}; the console message Chromium also writes
 *       names the <em>parent</em> page, so it cannot be tied back to a frame and is not used.
 *   <li><strong>Maps.</strong> Spec 7.1's named case: the real failure is billing or an API key,
 *       and "the iframe loaded" passes a grey tile with a <em>for development purposes only</em>
 *       watermark. The provider's error code in the console is the signal.
 *   <li><strong>Empty.</strong> A same-origin frame whose document has no text at all.
 * </ol>
 *
 * <p>Deviation D17: emptiness is never reported for a cross-origin frame. Measured, a healthy
 * cross-origin embed and a blocked one both report {@code contentTextLength = 0}, because the
 * parent cannot read either document — so the rule would fire on every healthy YouTube and Maps
 * embed on every page.
 */
public final class IframeEmbedCheck implements PageCheck {

    static final String BLOCKED = "finding.IFRAME_EMBED.blocked";
    static final String MAPS = "finding.IFRAME_EMBED.maps";
    static final String EMPTY = "finding.IFRAME_EMBED.empty";

    /** The codes Google Maps writes to the console when a key or the billing account is wrong. */
    static final List<String> MAPS_ERROR_CODES = List.of(
            "ApiNotActivatedMapError", "BillingNotEnabledMapError", "InvalidKeyMapError",
            "MissingKeyMapError", "ExpiredKeyMapError", "RefererNotAllowedMapError",
            "DeletedApiProjectMapError", "OverQuotaMapError");

    @Override
    public CheckType type() {
        return CheckType.IFRAME_EMBED;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(BLOCKED, MAPS, EMPTY);
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        if (!snapshot.reachable()) {
            return List.of();
        }
        Set<String> blocked = snapshot.failedRequests().stream()
                .filter(request -> "document".equals(request.resourceType()))
                .map(FailedRequest::url)
                .map(UrlNormalizer::key)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());
        List<String> mapsErrors = snapshot.errors().stream()
                .map(ConsoleMessage::text)
                .filter(text -> text != null && mapsCodeIn(text) != null)
                .toList();

        List<CheckFinding> findings = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        for (FrameRef frame : snapshot.frames()) {
            String subject = frame.src().value();
            if (!reported.add(subject)) {
                continue;
            }
            if (blocked.contains(subject)) {
                findings.add(finding(snapshot, config, BLOCKED, subject, List.of(subject),
                        List.of()));
            } else if (isMapsEmbed(frame.src()) && !mapsErrors.isEmpty()) {
                findings.add(finding(snapshot, config, MAPS, subject,
                        List.of(mapsCodeIn(mapsErrors.getFirst())), mapsErrors));
            } else if (frame.sameOrigin() && frame.contentTextLength() == 0) {
                findings.add(finding(snapshot, config, EMPTY, subject, List.of(subject),
                        List.of()));
            }
        }
        return findings;
    }

    private CheckFinding finding(PageSnapshot snapshot, CheckConfig config, String messageKey,
            String subject, List<String> args, List<String> console) {
        return new CheckFinding(type(), config.severity(), subject, snapshot.url(), messageKey,
                args, new Evidence(snapshot.screenshotPath(), null, null, null, console));
    }

    /** The provider's code inside a longer console line, or null when there is none. */
    private static String mapsCodeIn(String text) {
        return MAPS_ERROR_CODES.stream().filter(text::contains).findFirst().orElse(null);
    }

    private static boolean isMapsEmbed(NormalizedUrl src) {
        String path = src.path().toLowerCase(Locale.ROOT);
        return path.contains("/maps/embed")
                || (src.registrableHost().contains("google") && path.contains("/maps"));
    }
}