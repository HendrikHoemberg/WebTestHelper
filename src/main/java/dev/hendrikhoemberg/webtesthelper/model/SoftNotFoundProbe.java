package dev.hendrikhoemberg.webtesthelper.model;

/** What the {baseUrl}/{uuid} probe learned about the site's not-found page (spec 7.1). */
public record SoftNotFoundProbe(int httpStatus, long simhash, int textLength) {
    public static final SoftNotFoundProbe NONE = new SoftNotFoundProbe(0, 0L, 0);

    public boolean usable() {
        return httpStatus == 200 && textLength > 0;
    }
}