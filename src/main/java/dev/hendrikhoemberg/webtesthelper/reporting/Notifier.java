package dev.hendrikhoemberg.webtesthelper.reporting;

public interface Notifier {
    void deliver(OutboundMail mail) throws MailDeliveryException;
}
