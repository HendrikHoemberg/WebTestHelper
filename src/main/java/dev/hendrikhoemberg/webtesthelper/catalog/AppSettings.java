package dev.hendrikhoemberg.webtesthelper.catalog;

import dev.hendrikhoemberg.webtesthelper.catalog.persistence.AppSettingEntity;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.AppSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
    public static final String KEY_MAIL_BASE_URL = "mail.base-url";
    public static final String KEY_MAIL_REDIRECT_ALL_TO = "mail.redirect-all-to";

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
