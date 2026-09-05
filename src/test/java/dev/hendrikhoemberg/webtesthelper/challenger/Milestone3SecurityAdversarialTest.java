package dev.hendrikhoemberg.webtesthelper.challenger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.hendrikhoemberg.webtesthelper.auth.AppUserService;
import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.ImapSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.SecretBox;
import dev.hendrikhoemberg.webtesthelper.catalog.SmtpSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.TlsMode;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.AppSettingEntity;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.AppSettingRepository;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;
import dev.hendrikhoemberg.webtesthelper.model.StepAction;
import dev.hendrikhoemberg.webtesthelper.recorder.CapturedEvent;
import dev.hendrikhoemberg.webtesthelper.recorder.CapturedEvent.EventKind;
import dev.hendrikhoemberg.webtesthelper.recorder.StepBuilder;
import dev.hendrikhoemberg.webtesthelper.reporting.MailRenderer;
import dev.hendrikhoemberg.webtesthelper.reporting.OutboxService;
import dev.hendrikhoemberg.webtesthelper.reporting.WebhookNotifier;
import dev.hendrikhoemberg.webtesthelper.runner.CapacityService;
import dev.hendrikhoemberg.webtesthelper.runner.SystemCapacity;
import dev.hendrikhoemberg.webtesthelper.web.SecurityConfig;
import dev.hendrikhoemberg.webtesthelper.web.SettingsController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Empirical Challenger Test Suite for Milestone 3 (Phase 3 P2 Remediation):
 * 1. AppSettings & SecretBox: Adversarially corrupted ciphertext resilience, warning logs, no 500 error, no admin lockout.
 * 2. StepBuilder Password Redaction: Non-standard inputs with inputType=password redacted unconditionally.
 * 4. Content Security Policy: Response header presence, directive safety, clickjacking & script execution constraints.
 */
@org.junit.jupiter.api.parallel.ResourceLock("spring-context")
@WebMvcTest({SecurityConfig.class, SettingsController.class})
class Milestone3SecurityAdversarialTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AppSettings appSettings;

    @MockitoBean
    MailRenderer mailRenderer;

    @MockitoBean
    OutboxService outboxService;

    @MockitoBean
    AppUserService appUserService;

    @MockitoBean
    CapacityService capacityService;

    @MockitoBean
    WebhookNotifier webhookNotifier;

    // =========================================================================
    // 1. AppSettings & SecretBox: Corrupted Ciphertext Resilience
    // =========================================================================
    @Nested
    @DisplayName("Area 1: SecretBox & AppSettings Corrupted Ciphertext Adversarial Verification")
    class SecretBoxAndAppSettingsTests {

        @Test
        void secretBoxRejectsCorruptedCiphertextWithIllegalStateException(@TempDir Path tempDirA, @TempDir Path tempDirB) {
            SecretBox boxA = new SecretBox(tempDirA);
            SecretBox boxB = new SecretBox(tempDirB); // Distinct key
            String plain = "SuperSecretPassword123!";
            String validCiphertext = boxA.encrypt(plain);
            byte[] rawBytes = Base64.getDecoder().decode(validCiphertext);

            // 1. Invalid Base64 strings
            assertThatThrownBy(() -> boxA.decrypt("not-a-valid-base64!@#$"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Decryption failed");
            assertThatThrownBy(() -> boxA.decrypt("==="))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> boxA.decrypt(";;;;"))
                    .isInstanceOf(IllegalStateException.class);

            // 2. Truncated byte sequences (< 12 bytes IV)
            String tooShort = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
            assertThatThrownBy(() -> boxA.decrypt(tooShort))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Decryption failed");

            // 3. Truncated byte sequences between IV and GCM tag (< 12 + 16 bytes)
            byte[] ivOnly = new byte[12];
            System.arraycopy(rawBytes, 0, ivOnly, 0, 12);
            String ivOnlyBase64 = Base64.getEncoder().encodeToString(ivOnly);
            assertThatThrownBy(() -> boxA.decrypt(ivOnlyBase64))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Decryption failed");

            // 4. Corrupted IV (flip bits in first 12 bytes)
            byte[] corruptedIvBytes = rawBytes.clone();
            corruptedIvBytes[0] ^= 0x55;
            String corruptedIvBase64 = Base64.getEncoder().encodeToString(corruptedIvBytes);
            assertThatThrownBy(() -> boxA.decrypt(corruptedIvBase64))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Decryption failed");

            // 5. Corrupted Ciphertext Body
            byte[] corruptedPayloadBytes = rawBytes.clone();
            corruptedPayloadBytes[15] ^= 0xAA;
            String corruptedPayloadBase64 = Base64.getEncoder().encodeToString(corruptedPayloadBytes);
            assertThatThrownBy(() -> boxA.decrypt(corruptedPayloadBase64))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Decryption failed");

            // 6. Corrupted GCM Tag (last 16 bytes)
            byte[] corruptedTagBytes = rawBytes.clone();
            corruptedTagBytes[corruptedTagBytes.length - 1] ^= 0xFF;
            String corruptedTagBase64 = Base64.getEncoder().encodeToString(corruptedTagBytes);
            assertThatThrownBy(() -> boxA.decrypt(corruptedTagBase64))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Decryption failed");

            // 7. Mismatched Key (encrypted with Box A, decrypted with Box B)
            assertThatThrownBy(() -> boxB.decrypt(validCiphertext))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Decryption failed");

            // 8. Null input returns null cleanly
            assertThat(boxA.decrypt(null)).isNull();
        }

        @Test
        void appSettingsGracefullyHandlesCorruptedCiphertextAndLogsWarning(@TempDir Path tempDir) {
            SecretBox secretBox = new SecretBox(tempDir);
            AppSettingRepository repository = mock(AppSettingRepository.class);
            AppSettings appSettingsUnderTest = new AppSettings(repository, secretBox);

            // Prepare diverse corrupted ciphertexts
            String validCiphertext = secretBox.encrypt("Secret123");
            byte[] rawBytes = Base64.getDecoder().decode(validCiphertext);
            byte[] corruptedIv = rawBytes.clone();
            corruptedIv[0] ^= 0x77;

            List<String> adversarialCorruptedCiphertexts = List.of(
                    "totally-invalid-base64!!",
                    Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}), // < 12 bytes
                    Base64.getEncoder().encodeToString(new byte[12]),        // IV only, no tag
                    Base64.getEncoder().encodeToString(corruptedIv),        // Corrupted IV
                    Base64.getEncoder().encodeToString(new byte[20])         // Truncated tag
            );

            Logger logger = (Logger) LoggerFactory.getLogger(AppSettings.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);

            try {
                for (String corrupted : adversarialCorruptedCiphertexts) {
                    appender.list.clear();

                    // SMTP test
                    AppSettingEntity smtpHost = new AppSettingEntity(AppSettings.KEY_SMTP_HOST, "smtp.example.com", false);
                    AppSettingEntity smtpPassword = new AppSettingEntity(AppSettings.KEY_SMTP_PASSWORD, corrupted, true);
                    when(repository.findById(AppSettings.KEY_SMTP_HOST)).thenReturn(Optional.of(smtpHost));
                    when(repository.findById(AppSettings.KEY_SMTP_PASSWORD)).thenReturn(Optional.of(smtpPassword));

                    SmtpSettings smtp = appSettingsUnderTest.smtp();
                    assertThat(smtp).isNotNull();
                    assertThat(smtp.password()).as("Corrupted ciphertext '%s' must resolve to null password", corrupted).isNull();

                    boolean hasSmtpWarn = appender.list.stream().anyMatch(event ->
                            event.getLevel() == Level.WARN
                                    && event.getFormattedMessage().contains("Entschlüsselung des SMTP-Passworts fehlgeschlagen"));
                    assertThat(hasSmtpWarn).as("Expected WARN log for corrupted SMTP password '%s'", corrupted).isTrue();

                    appender.list.clear();

                    // IMAP test
                    AppSettingEntity imapHost = new AppSettingEntity(AppSettings.KEY_IMAP_HOST, "imap.example.com", false);
                    AppSettingEntity imapPassword = new AppSettingEntity(AppSettings.KEY_IMAP_PASSWORD, corrupted, true);
                    when(repository.findById(AppSettings.KEY_IMAP_HOST)).thenReturn(Optional.of(imapHost));
                    when(repository.findById(AppSettings.KEY_IMAP_PASSWORD)).thenReturn(Optional.of(imapPassword));

                    ImapSettings imap = appSettingsUnderTest.imap();
                    assertThat(imap).isNotNull();
                    assertThat(imap.password()).as("Corrupted ciphertext '%s' must resolve to null password", corrupted).isNull();

                    boolean hasImapWarn = appender.list.stream().anyMatch(event ->
                            event.getLevel() == Level.WARN
                                    && event.getFormattedMessage().contains("Entschlüsselung des IMAP-Passworts fehlgeschlagen"));
                    assertThat(hasImapWarn).as("Expected WARN log for corrupted IMAP password '%s'", corrupted).isTrue();
                }
            } finally {
                logger.detachAppender(appender);
            }
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void settingsControllerRendersCleanlyWithout500WhenPasswordsAreCorrupted() throws Exception {
            when(capacityService.current(anyInt())).thenReturn(new SystemCapacity(4, 1, 3, 1, Duration.ofSeconds(30), 2));

            // Stub AppSettings returning null passwords (the result of corrupt ciphertexts)
            SmtpSettings corruptedSmtp = new SmtpSettings("smtp.example.com", 587, TlsMode.STARTTLS, "smtp-user", null, "admin@example.com");
            ImapSettings corruptedImap = new ImapSettings("imap.example.com", 993, TlsMode.STARTTLS, "imap-user", null, "INBOX", "verify@example.com");

            when(appSettings.smtp()).thenReturn(corruptedSmtp);
            when(appSettings.imap()).thenReturn(corruptedImap);
            when(appSettings.baseUrl()).thenReturn("https://monitor.example.com");
            when(appSettings.redirectAllMailTo()).thenReturn(Optional.empty());
            when(appSettings.schedulingPaused()).thenReturn(false);
            when(appSettings.fallbackRecipients()).thenReturn(List.of("fallback@example.com"));
            when(appSettings.webhookUrl()).thenReturn("");
            when(appSettings.webhookEnabled()).thenReturn(false);
            when(appSettings.webhookOnlyCritical()).thenReturn(true);

            // Administrator visits /einstellungen -> MUST return 200 OK without 500 error or exception
            mvc.perform(get("/einstellungen"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("einstellungen/index"))
                    .andExpect(model().attributeExists("form"))
                    .andExpect(content().string(containsString("smtp.example.com")))
                    .andExpect(content().string(containsString("imap.example.com")))
                    // Verify no 500 stack trace or internal exception is shown
                    .andExpect(content().string(not(containsString("Whitelabel Error Page"))))
                    .andExpect(content().string(not(containsString("IllegalStateException"))))
                    .andExpect(content().string(not(containsString("Decryption failed"))));
        }
    }

    // =========================================================================
    // 2. StepBuilder Password Redaction: Non-Standard Input Configurations
    // =========================================================================
    @Nested
    @DisplayName("Area 2: StepBuilder Password Redaction Adversarial Verification")
    class StepBuilderPasswordRedactionTests {

        private static final String START_URL = "https://app.example.com/login";
        private static final String SENSITIVE_VALUE = "MyTopSecretToken!2026#";

        @ParameterizedTest(name = "inputType={0} unconditionally redacts password")
        @ValueSource(strings = {"password", "PASSWORD", "Password", "pAsSwOrD", "PassWord"})
        void nonStandardInputsWithPasswordTypeAreRedactedUnconditionally(String inputType) {
            // Field attributes have ZERO password keywords (e.g. name="random_key", testId="token-entry")
            CapturedEvent event = new CapturedEvent(
                    EventKind.INPUT,
                    "input",
                    "random_key_99",
                    "custom-token-entry",
                    "textbox",
                    "Eingabefeld",
                    "Geheimer Code",
                    null,
                    SENSITIVE_VALUE,
                    "div.form-group > input.styled-control",
                    inputType
            );

            List<JourneyStep> steps = StepBuilder.build(List.of(event), START_URL);

            assertThat(steps).hasSize(2);
            JourneyStep step = steps.get(1);
            assertThat(step.action()).isEqualTo(StepAction.FILL);
            assertThat(step.value()).as("Password must be unconditionally redacted to empty string").isEmpty();

            // Assert plaintext value does not appear anywhere in step, string representation, or locator candidates
            assertThat(step.toString()).doesNotContain(SENSITIVE_VALUE);
            for (LocatorCandidate candidate : step.locatorCandidates()) {
                assertThat(candidate.value()).doesNotContain(SENSITIVE_VALUE);
            }
        }

        @Test
        void nonStandardElementStructureWithPasswordTypeIsRedacted() {
            // Obscure / custom tag name, all metadata null except inputType="password"
            CapturedEvent bareEvent = new CapturedEvent(
                    EventKind.INPUT,
                    "custom-input",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    SENSITIVE_VALUE,
                    "body > custom-input",
                    "password"
            );

            List<JourneyStep> steps = StepBuilder.build(List.of(bareEvent), START_URL);

            assertThat(steps).hasSize(2);
            JourneyStep step = steps.get(1);
            assertThat(step.action()).isEqualTo(StepAction.FILL);
            assertThat(step.value()).isEmpty();
            assertThat(step.toString()).doesNotContain(SENSITIVE_VALUE);
        }

        @Test
        void deceptiveLabelAndNameDoesNotEvadeRedactionWhenInputTypeIsPassword() {
            // Label and name explicitly claim to be "username", but inputType is "password"
            CapturedEvent deceptiveEvent = new CapturedEvent(
                    EventKind.INPUT,
                    "input",
                    "username_field",
                    "input-username",
                    "textbox",
                    "Benutzername",
                    "Benutzername",
                    null,
                    SENSITIVE_VALUE,
                    "form > input#username_field",
                    "password"
            );

            List<JourneyStep> steps = StepBuilder.build(List.of(deceptiveEvent), START_URL);

            assertThat(steps).hasSize(2);
            JourneyStep step = steps.get(1);
            assertThat(step.action()).isEqualTo(StepAction.FILL);
            assertThat(step.value()).as("Input type 'password' must override misleading username label").isEmpty();
        }

        @Test
        void consecutiveTypingOnNonStandardPasswordFieldCollapsesAndRedacts() {
            CapturedEvent k1 = new CapturedEvent(
                    EventKind.INPUT, "input", "random_key", null, "textbox",
                    "Code", null, null, "s", "input#random_key", "PASSWORD"
            );
            CapturedEvent k2 = new CapturedEvent(
                    EventKind.INPUT, "input", "random_key", null, "textbox",
                    "Code", null, null, "sec", "input#random_key", "PASSWORD"
            );
            CapturedEvent k3 = new CapturedEvent(
                    EventKind.INPUT, "input", "random_key", null, "textbox",
                    "Code", null, null, SENSITIVE_VALUE, "input#random_key", "PASSWORD"
            );

            List<JourneyStep> steps = StepBuilder.build(List.of(k1, k2, k3), START_URL);

            assertThat(steps).hasSize(2);
            JourneyStep step = steps.get(1);
            assertThat(step.action()).isEqualTo(StepAction.FILL);
            assertThat(step.value()).isEmpty();
            assertThat(step.toString()).doesNotContain("sec");
            assertThat(step.toString()).doesNotContain(SENSITIVE_VALUE);
        }

        @Test
        void regularInputsAreNotOverRedacted() {
            // Negative controls: regular inputs without password types must retain their values
            CapturedEvent textEvent = new CapturedEvent(
                    EventKind.INPUT, "input", "user", null, "textbox",
                    "Benutzer", "Benutzer", null, "johndoe", "input#user", "text"
            );
            CapturedEvent emailEvent = new CapturedEvent(
                    EventKind.INPUT, "input", "mail", null, "textbox",
                    "E-Mail", "E-Mail", null, "john@example.com", "input#mail", "email"
            );
            CapturedEvent searchEvent = new CapturedEvent(
                    EventKind.INPUT, "input", "q", null, "searchbox",
                    "Suche", "Suche", null, "spring boot modulith", "input#q", "search"
            );
            CapturedEvent nullTypeEvent = new CapturedEvent(
                    EventKind.INPUT, "input", "notes", null, "textbox",
                    "Notizen", "Notizen", null, "alles gut", "input#notes", null
            );

            List<JourneyStep> steps = StepBuilder.build(
                    List.of(textEvent, emailEvent, searchEvent, nullTypeEvent),
                    START_URL
            );

            assertThat(steps).hasSize(5); // GOTO + 4 FILL
            assertThat(steps.get(1).value()).isEqualTo("johndoe");
            assertThat(steps.get(2).value()).isEqualTo("john@example.com");
            assertThat(steps.get(3).value()).isEqualTo("spring boot modulith");
            assertThat(steps.get(4).value()).isEqualTo("alles gut");
        }
    }

    // =========================================================================
    // 4. Content Security Policy: Response Header Presence and Directive Safety
    // =========================================================================
    @Nested
    @DisplayName("Area 4: Content Security Policy Header Adversarial Verification")
    class ContentSecurityPolicyTests {

        private static final String EXPECTED_CSP_PREFIX = "default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self' ws: wss:; frame-ancestors 'self';";

        @Test
        void unauthenticatedLoginEndpointContainsContentSecurityPolicyHeader() throws Exception {
            mvc.perform(get("/anmelden"))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("Content-Security-Policy"))
                    .andExpect(header().string("Content-Security-Policy", EXPECTED_CSP_PREFIX))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("X-Frame-Options", "DENY"));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void protectedAdminSettingsEndpointContainsContentSecurityPolicyHeader() throws Exception {
            when(capacityService.current(anyInt())).thenReturn(new SystemCapacity(4, 1, 3, 1, Duration.ofSeconds(30), 2));
            when(appSettings.smtp()).thenReturn(new SmtpSettings(null, 587, TlsMode.STARTTLS, null, null, null));
            when(appSettings.imap()).thenReturn(new ImapSettings(null, 993, TlsMode.STARTTLS, null, null, "INBOX", null));
            when(appSettings.baseUrl()).thenReturn("");
            when(appSettings.redirectAllMailTo()).thenReturn(Optional.empty());
            when(appSettings.schedulingPaused()).thenReturn(false);
            when(appSettings.fallbackRecipients()).thenReturn(List.of());
            when(appSettings.webhookUrl()).thenReturn("");
            when(appSettings.webhookEnabled()).thenReturn(false);
            when(appSettings.webhookOnlyCritical()).thenReturn(true);

            mvc.perform(get("/einstellungen"))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("Content-Security-Policy"))
                    .andExpect(header().string("Content-Security-Policy", EXPECTED_CSP_PREFIX));
        }

        @Test
        void cspDirectivesEnforceStrictSafetyConstraints() throws Exception {
            var mvcResult = mvc.perform(get("/anmelden"))
                    .andExpect(status().isOk())
                    .andReturn();

            String cspHeader = mvcResult.getResponse().getHeader("Content-Security-Policy");
            assertThat(cspHeader).isNotNull();

            // 1. default-src must be restricted to 'self'
            assertThat(cspHeader).contains("default-src 'self'");
            assertThat(cspHeader).doesNotContain("default-src *");

            // 2. script-src must NOT allow arbitrary origins
            assertThat(cspHeader).contains("script-src 'self' 'unsafe-inline' 'unsafe-eval'");
            assertThat(cspHeader).doesNotContain("script-src *");
            assertThat(cspHeader).doesNotContain("https://");
            assertThat(cspHeader).doesNotContain("http://");

            // 3. connect-src must allow 'self' and local WebSocket protocols (for recorder), no wildcard
            assertThat(cspHeader).contains("connect-src 'self' ws: wss:");
            assertThat(cspHeader).doesNotContain("connect-src *");

            // 4. frame-ancestors must restrict framing to 'self' (clickjacking defense)
            assertThat(cspHeader).contains("frame-ancestors 'self'");
            assertThat(cspHeader).doesNotContain("frame-ancestors *");
        }
    }
}
