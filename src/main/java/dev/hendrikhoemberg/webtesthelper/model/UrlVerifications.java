package dev.hendrikhoemberg.webtesthelper.model;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public record UrlVerifications(Map<String, UrlVerification> byUrl) {

    public static final UrlVerifications EMPTY = new UrlVerifications(Map.of());

    public UrlVerifications {
        byUrl = Map.copyOf(byUrl);
    }

    public static UrlVerifications of(Collection<UrlVerification> verifications) {
        Map<String, UrlVerification> byUrl = new HashMap<>();
        for (UrlVerification verification : verifications) {
            byUrl.put(verification.url(), verification);
        }
        return new UrlVerifications(byUrl);
    }

    public Optional<UrlVerification> of(NormalizedUrl url) {
        return Optional.ofNullable(byUrl.get(url.value()));
    }

    public int size() {
        return byUrl.size();
    }
}