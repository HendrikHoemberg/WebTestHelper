package dev.hendrikhoemberg.webtesthelper.catalog;

import dev.hendrikhoemberg.webtesthelper.catalog.persistence.AppSettingEntity;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.AppSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class AppSettings {

    public static final String KEY_SMTP_HOST = "smtp.host";
    public static final String KEY_SMTP_PORT = "smtp.port";
    public static final String KEY_SMTP_TLS = "smtp.tls";
    public static final String KEY_SMTP_USERNAME = "smtp.username";
    public static final String KEY_SMTP_PASSWORD = "smtp.password";
    public static final String KEY_SMTP_FROM = "smtp.from";
    public static final String KEY_IMAP_HOST = "imap.host";
    public static final String KEY_IMAP_PORT = "imap.port";
    public static final String KEY_IMAP_TLS = "imap.tls";
    public static final String KEY_IMAP_USERNAME = "imap.username";
    public static final String KEY_IMAP_PASSWORD = "imap.password";
    public static final String KEY_IMAP_FOLDER = "imap.folder";
    public static final String KEY_IMAP_VERIFICATION_ADDRESS = "imap.verification-address";
    public static final String KEY_MAIL_BASE_URL = "mail.base-url";
    public static final String KEY_MAIL_REDIRECT_ALL_TO = "mail.redirect-all-to";
    public static final String KEY_MAIL_FALLBACK_RECIPIENTS = "mail.fallback-recipients";
    public static final String KEY_SCHEDULING_PAUSED = "scheduling.paused";

    private final AppSettingRepository repository;
    private final SecretBox secretBox;

    public AppSettings(AppSettingRepository repository, SecretBox secretBox) {
        this.repository = repository;
        this.secretBox = secretBox;
    }

    @Transactional(readOnly = true)
    public SmtpSettings smtp() {
        String host = getSetting(KEY_SMTP_HOST).orElse(null);

        int port = 587;
        Optional<String> portStr = getSetting(KEY_SMTP_PORT);
        if (portStr.isPresent() && !portStr.get().isBlank()) {
            try {
                port = Integer.parseInt(portStr.get().trim());
            } catch (NumberFormatException ignored) {
            }
        }

        TlsMode tls = TlsMode.STARTTLS;
        Optional<String> tlsStr = getSetting(KEY_SMTP_TLS);
        if (tlsStr.isPresent() && !tlsStr.get().isBlank()) {
            try {
                tls = TlsMode.valueOf(tlsStr.get().trim());
            } catch (IllegalArgumentException ignored) {
            }
        }

        String username = getSetting(KEY_SMTP_USERNAME).orElse(null);

        String password = null;
        Optional<AppSettingEntity> passwordEntity = repository.findById(KEY_SMTP_PASSWORD);
        if (passwordEntity.isPresent() && passwordEntity.get().getSettingValue() != null) {
            String raw = passwordEntity.get().getSettingValue();
            if (passwordEntity.get().isEncrypted()) {
                password = secretBox.decrypt(raw);
            } else {
                password = raw;
            }
        }

        String from = getSetting(KEY_SMTP_FROM).orElse(null);

        return new SmtpSettings(host, port, tls, username, password, from);
    }

    public void saveSmtp(SmtpSettings smtp) {
        saveSetting(KEY_SMTP_HOST, smtp.host(), false);
        saveSetting(KEY_SMTP_PORT, String.valueOf(smtp.port()), false);
        saveSetting(KEY_SMTP_TLS, smtp.tls() != null ? smtp.tls().name() : TlsMode.STARTTLS.name(), false);
        saveSetting(KEY_SMTP_USERNAME, smtp.username(), false);

        String encryptedPassword = (smtp.password() != null && !smtp.password().isEmpty())
                ? secretBox.encrypt(smtp.password())
                : null;
        saveSetting(KEY_SMTP_PASSWORD, encryptedPassword, true);

        saveSetting(KEY_SMTP_FROM, smtp.fromAddress(), false);
    }

    @Transactional(readOnly = true)
    public ImapSettings imap() {
        String host = getSetting(KEY_IMAP_HOST).orElse(null);

        int port = 993;
        Optional<String> portStr = getSetting(KEY_IMAP_PORT);
        if (portStr.isPresent() && !portStr.get().isBlank()) {
            try {
                port = Integer.parseInt(portStr.get().trim());
            } catch (NumberFormatException ignored) {
            }
        }

        TlsMode tls = TlsMode.STARTTLS;
        Optional<String> tlsStr = getSetting(KEY_IMAP_TLS);
        if (tlsStr.isPresent() && !tlsStr.get().isBlank()) {
            try {
                tls = TlsMode.valueOf(tlsStr.get().trim());
            } catch (IllegalArgumentException ignored) {
            }
        }

        String username = getSetting(KEY_IMAP_USERNAME).orElse(null);

        String password = null;
        Optional<AppSettingEntity> passwordEntity = repository.findById(KEY_IMAP_PASSWORD);
        if (passwordEntity.isPresent() && passwordEntity.get().getSettingValue() != null) {
            String raw = passwordEntity.get().getSettingValue();
            if (passwordEntity.get().isEncrypted()) {
                password = secretBox.decrypt(raw);
            } else {
                password = raw;
            }
        }

        String folder = getSetting(KEY_IMAP_FOLDER).orElse("INBOX");
        String verificationAddress = getSetting(KEY_IMAP_VERIFICATION_ADDRESS).orElse(null);

        return new ImapSettings(host, port, tls, username, password, folder, verificationAddress);
    }

    public void saveImap(ImapSettings imap) {
        saveSetting(KEY_IMAP_HOST, imap.host(), false);
        saveSetting(KEY_IMAP_PORT, String.valueOf(imap.port()), false);
        saveSetting(KEY_IMAP_TLS, imap.tls() != null ? imap.tls().name() : TlsMode.STARTTLS.name(), false);
        saveSetting(KEY_IMAP_USERNAME, imap.username(), false);

        if (imap.password() != null && !imap.password().isBlank()) {
            saveSetting(KEY_IMAP_PASSWORD, secretBox.encrypt(imap.password()), true);
        } else if (!repository.findById(KEY_IMAP_PASSWORD).isPresent()) {
            saveSetting(KEY_IMAP_PASSWORD, null, true);
        }

        saveSetting(KEY_IMAP_FOLDER, imap.folder() != null && !imap.folder().isBlank() ? imap.folder() : "INBOX", false);
        saveSetting(KEY_IMAP_VERIFICATION_ADDRESS, imap.verificationAddress(), false);
    }

    @Transactional(readOnly = true)
    public String baseUrl() {
        return getSetting(KEY_MAIL_BASE_URL).orElse("");
    }

    public void saveBaseUrl(String baseUrl) {
        String normalised = (baseUrl != null) ? baseUrl.strip() : "";
        while (normalised.endsWith("/")) {
            normalised = normalised.substring(0, normalised.length() - 1);
        }
        saveSetting(KEY_MAIL_BASE_URL, normalised, false);
    }

    @Transactional(readOnly = true)
    public Optional<String> redirectAllMailTo() {
        return getSetting(KEY_MAIL_REDIRECT_ALL_TO)
                .filter(v -> !v.isBlank())
                .map(String::strip);
    }

    public void saveRedirectAllMailTo(String address) {
        String val = (address != null && !address.isBlank()) ? address.strip() : "";
        saveSetting(KEY_MAIL_REDIRECT_ALL_TO, val, false);
    }

    @Transactional(readOnly = true)
    public List<String> fallbackRecipients() {
        return getSetting(KEY_MAIL_FALLBACK_RECIPIENTS)
                .map(this::parseRecipients)
                .orElseGet(List::of);
    }

    public void saveFallbackRecipients(String raw) {
        List<String> parsed = parseRecipients(raw);
        String val = String.join(", ", parsed);
        saveSetting(KEY_MAIL_FALLBACK_RECIPIENTS, val, false);
    }

    private List<String> parseRecipients(String raw) {
        return EmailAddresses.split(raw);
    }

    @Transactional(readOnly = true)
    public boolean schedulingPaused() {
        return getSetting(KEY_SCHEDULING_PAUSED).map(Boolean::parseBoolean).orElse(false);
    }

    public void saveSchedulingPaused(boolean paused) {
        saveSetting(KEY_SCHEDULING_PAUSED, Boolean.toString(paused), false);
    }

    private Optional<String> getSetting(String key) {
        return repository.findById(key).map(AppSettingEntity::getSettingValue);
    }

    private void saveSetting(String key, String value, boolean encrypted) {
        AppSettingEntity entity = repository.findById(key)
                .orElseGet(() -> new AppSettingEntity(key, value, encrypted));
        entity.setSettingValue(value);
        entity.setEncrypted(encrypted);
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
    }
}
