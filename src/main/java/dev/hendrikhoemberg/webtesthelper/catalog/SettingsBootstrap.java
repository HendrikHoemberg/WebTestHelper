package dev.hendrikhoemberg.webtesthelper.catalog;

import dev.hendrikhoemberg.webtesthelper.catalog.persistence.AppSettingEntity;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.AppSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public class SettingsBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SettingsBootstrap.class);

    private final AppSettingRepository repository;
    private final SecretBox secretBox;
    private final Environment environment;

    public SettingsBootstrap(AppSettingRepository repository, SecretBox secretBox, Environment environment) {
        this.repository = repository;
        this.secretBox = secretBox;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrapSetting(AppSettings.KEY_SMTP_HOST, "WTH_SMTP_HOST", "webtesthelper.smtp.host", false);
        bootstrapSetting(AppSettings.KEY_SMTP_PORT, "WTH_SMTP_PORT", "webtesthelper.smtp.port", false);
        bootstrapSetting(AppSettings.KEY_SMTP_TLS, "WTH_SMTP_TLS", "webtesthelper.smtp.tls", false);
        bootstrapSetting(AppSettings.KEY_SMTP_USERNAME, "WTH_SMTP_USER", "webtesthelper.smtp.user", false);
        bootstrapSetting(AppSettings.KEY_SMTP_PASSWORD, "WTH_SMTP_PASSWORD", "webtesthelper.smtp.password", true);
        bootstrapSetting(AppSettings.KEY_SMTP_FROM, "WTH_SMTP_FROM", "webtesthelper.smtp.from", false);
        bootstrapSetting(AppSettings.KEY_MAIL_BASE_URL, "WTH_BASE_URL", "webtesthelper.base-url", false);
    }

    private void bootstrapSetting(String settingKey, String envKey, String propertyKey, boolean encrypted) {
        if (repository.findById(settingKey).isPresent()) {
            return;
        }

        String value = getValue(envKey, propertyKey);
        if (value == null || value.isBlank()) {
            return;
        }

        String finalValue = value.strip();
        if (AppSettings.KEY_MAIL_BASE_URL.equals(settingKey)) {
            while (finalValue.endsWith("/")) {
                finalValue = finalValue.substring(0, finalValue.length() - 1);
            }
        }

        if (encrypted) {
            finalValue = secretBox.encrypt(finalValue);
        }

        AppSettingEntity entity = new AppSettingEntity(settingKey, finalValue, encrypted, Instant.now());
        repository.save(entity);
        log.info("Bootstrapped setting '{}' from environment", settingKey);
    }

    private String getValue(String envKey, String propertyKey) {
        String val = environment.getProperty(envKey);
        if (val != null && !val.isBlank()) {
            return val;
        }
        val = environment.getProperty(propertyKey);
        if (val != null && !val.isBlank()) {
            return val;
        }
        return null;
    }
}
