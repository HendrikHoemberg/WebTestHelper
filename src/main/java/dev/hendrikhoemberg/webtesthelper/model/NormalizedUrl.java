package dev.hendrikhoemberg.webtesthelper.model;

import java.util.Objects;

/**
 * A URL reduced to the canonical form used as the crawl frontier's dedupe key, the
 * external URL cache's primary key and a finding's {@code subjectKey}.
 *
 * <p>The fragment is not represented: it is dropped during normalisation and never
 * recovered.
 */
public record NormalizedUrl(String scheme, String host, int port, String path, String query) {

    public NormalizedUrl {
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(path, "path");
        if (query != null && query.isEmpty()) {
            query = null;
        }
    }

    public static int defaultPort(String scheme) {
        return "https".equals(scheme) ? 443 : 80;
    }

    public boolean hasDefaultPort() {
        return port == defaultPort(scheme);
    }

    /** The canonical string form. This is what gets stored and compared. */
    public String value() {
        StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
        if (!hasDefaultPort()) {
            sb.append(':').append(port);
        }
        sb.append(path);
        if (query != null) {
            sb.append('?').append(query);
        }
        return sb.toString();
    }

    public String origin() {
        StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
        if (!hasDefaultPort()) {
            sb.append(':').append(port);
        }
        return sb.toString();
    }

    /** Host with a leading {@code www.} removed, so the apex and the www host compare equal. */
    public String registrableHost() {
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    public boolean sameSiteAs(NormalizedUrl other) {
        return other != null && registrableHost().equals(other.registrableHost());
    }

    /** Path plus surviving query — a finding's {@code locationKey}. */
    public String locationKey() {
        return query == null ? path : path + "?" + query;
    }

    public boolean isSecure() {
        return "https".equals(scheme);
    }

    @Override
    public String toString() {
        return value();
    }
}
