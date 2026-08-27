package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.ImapSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.TlsMode;
import dev.hendrikhoemberg.webtesthelper.model.Mailbox;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.BodyTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Properties;

/**
 * IMAP implementation of {@link Mailbox} (D89, D95).
 * Reads current {@link ImapSettings} from {@link AppSettings} on every call.
 */
@Component
public class ImapMailbox implements Mailbox {

    private static final Logger log = LoggerFactory.getLogger(ImapMailbox.class);

    private static final long[] SCHEDULE_SECONDS = {0, 3, 8, 15, 25, 40, 60};

    private final AppSettings appSettings;

    public ImapMailbox(AppSettings appSettings) {
        this.appSettings = appSettings;
    }

    @Override
    public String address() {
        ImapSettings imap = appSettings.imap();
        if (imap != null && imap.verificationAddress() != null) {
            return imap.verificationAddress();
        }
        return "";
    }

    @Override
    public Result awaitToken(String token, Duration budget) {
        if (token == null || token.isBlank()) {
            return Result.UNAVAILABLE;
        }

        ImapSettings imap = appSettings.imap();
        if (imap == null || !imap.configured()) {
            return Result.UNAVAILABLE;
        }

        Duration effectiveBudget = budget != null ? budget : Duration.ofSeconds(60);
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + effectiveBudget.toNanos();

        for (long offsetSec : SCHEDULE_SECONDS) {
            long scheduledNanos = startNanos + Duration.ofSeconds(offsetSec).toNanos();
            long now = System.nanoTime();

            if (scheduledNanos > deadlineNanos) {
                long sleepNanos = deadlineNanos - now;
                if (sleepNanos > 0) {
                    if (!sleep(sleepNanos)) {
                        return Result.UNAVAILABLE;
                    }
                }
                return Result.NOT_FOUND;
            }

            if (scheduledNanos > now) {
                long sleepNanos = scheduledNanos - now;
                if (!sleep(sleepNanos)) {
                    return Result.UNAVAILABLE;
                }
                now = System.nanoTime();
                if (now >= deadlineNanos) {
                    return Result.NOT_FOUND;
                }
            }

            PollAttempt attempt = pollOnce(imap, token);
            if (attempt == PollAttempt.FOUND) {
                return Result.FOUND;
            }
            if (attempt == PollAttempt.UNAVAILABLE) {
                return Result.UNAVAILABLE;
            }
        }

        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos > 0) {
            if (!sleep(remainingNanos)) {
                return Result.UNAVAILABLE;
            }
        }
        return Result.NOT_FOUND;
    }

    private boolean sleep(long nanos) {
        try {
            long millis = nanos / 1_000_000L;
            int remainingNanos = (int) (nanos % 1_000_000L);
            Thread.sleep(millis, remainingNanos);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private enum PollAttempt {
        FOUND,
        NOT_FOUND,
        UNAVAILABLE
    }

    private PollAttempt pollOnce(ImapSettings imap, String token) {
        Properties props = new Properties();
        String protocol;
        int defaultPort;
        if (imap.tls() == TlsMode.SSL) {
            protocol = "imaps";
            defaultPort = 993;
            props.put("mail.imaps.ssl.enable", "true");
            props.put("mail.imaps.connectiontimeout", "10000");
            props.put("mail.imaps.timeout", "10000");
        } else {
            protocol = "imap";
            defaultPort = 143;
            if (imap.tls() == TlsMode.STARTTLS) {
                props.put("mail.imap.starttls.enable", "true");
                props.put("mail.imap.starttls.required", "true");
            } else {
                props.put("mail.imap.starttls.enable", "false");
            }
            props.put("mail.imap.connectiontimeout", "10000");
            props.put("mail.imap.timeout", "10000");
        }

        Session session = Session.getInstance(props);
        int port = imap.port() > 0 ? imap.port() : defaultPort;
        Store store = null;
        Folder folder = null;
        try {
            store = session.getStore(protocol);
            store.connect(imap.host(), port, imap.username(), imap.password());
            String folderName = (imap.folder() != null && !imap.folder().isBlank()) ? imap.folder() : "INBOX";
            folder = store.getFolder(folderName);
            folder.open(Folder.READ_ONLY);
            Message[] matches = folder.search(new BodyTerm(token));
            if (matches != null && matches.length > 0) {
                return PollAttempt.FOUND;
            }
            return PollAttempt.NOT_FOUND;
        } catch (MessagingException | RuntimeException e) {
            log.warn("IMAP poll failed: {}", e.getMessage());
            return PollAttempt.UNAVAILABLE;
        } finally {
            if (folder != null && folder.isOpen()) {
                try {
                    folder.close(false);
                } catch (MessagingException ignored) {
                }
            }
            if (store != null && store.isConnected()) {
                try {
                    store.close();
                } catch (MessagingException ignored) {
                }
            }
        }
    }
}
