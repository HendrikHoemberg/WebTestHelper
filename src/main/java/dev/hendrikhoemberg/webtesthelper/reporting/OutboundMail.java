package dev.hendrikhoemberg.webtesthelper.reporting;

public record OutboundMail(
        String recipient,
        String subject,
        String html,
        String text
) {
}
