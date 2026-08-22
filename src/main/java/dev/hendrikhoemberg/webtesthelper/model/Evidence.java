package dev.hendrikhoemberg.webtesthelper.model;

import java.util.List;

/**
 * What lets an employee judge a finding in five seconds instead of re-checking it by hand
 * (spec 8). Every component is optional; a check fills in what it actually observed and leaves
 * the rest null, because inventing evidence is worse than having none.
 */
public record Evidence(String screenshotPath, Integer httpStatus, String requestDetail,
                       String responseDetail, List<String> consoleExcerpt) {

    public static final Evidence NONE = new Evidence(null, null, null, null, List.of());

    public Evidence {
        consoleExcerpt = consoleExcerpt == null ? List.of() : List.copyOf(consoleExcerpt);
    }

    /** Screenshot and status of the page the finding was observed on. */
    public static Evidence ofPage(PageSnapshot snapshot) {
        return new Evidence(snapshot.screenshotPath(),
                snapshot.reachable() ? snapshot.httpStatus() : null, null, null, List.of());
    }
}