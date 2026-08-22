package dev.hendrikhoemberg.webtesthelper.model;

/** A network request the page made that did not complete cleanly. */
public record FailedRequest(String url, String method, String resourceType,
                            Integer status, String failureText) {}