package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.RecipientService;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.runner.RunSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Coordinates the digest cycle across all run scopes (§11.1, D53, D54, D56).
 */
@Service
@Transactional
public class DigestService {

    private static final Logger log = LoggerFactory.getLogger(DigestService.class);

    private final RunService runService;
    private final DigestAssembler assembler;
    private final RecipientService recipientService;
    private final DigestMailRenderer renderer;
    private final OutboxService outbox;
    private final AppSettings appSettings;
    private final ReportingProperties properties;
    private final WebhookNotifier webhookNotifier;

    public DigestService(
            RunService runService,
            DigestAssembler assembler,
            RecipientService recipientService,
            DigestMailRenderer renderer,
            OutboxService outbox,
            AppSettings appSettings,
            ReportingProperties properties,
            WebhookNotifier webhookNotifier
    ) {
        this.runService = runService;
        this.assembler = assembler;
        this.recipientService = recipientService;
        this.renderer = renderer;
        this.outbox = outbox;
        this.appSettings = appSettings;
        this.properties = properties;
        this.webhookNotifier = webhookNotifier;
    }

    public int runCycle(Instant now) {
        Objects.requireNonNull(now, "now must not be null");

        int enqueuedCount = 0;
        for (RunScope scope : RunScope.values()) {
            List<RunSummary> undigested = runService.undigested(scope);
            boolean inFlight = runService.hasRunsInFlight(scope);
            Optional<DigestWindow> windowOpt = DigestWindow.close(
                    scope,
                    undigested,
                    inFlight,
                    now,
                    properties.digestSettle(),
                    properties.digestMaxWait()
            );

            if (windowOpt.isPresent()) {
                DigestWindow window = windowOpt.get();
                Digest digest = assembler.assemble(window, Locale.GERMAN);

                Set<Long> siteIds = window.runs().stream()
                        .map(RunSummary::siteId)
                        .collect(Collectors.toCollection(LinkedHashSet::new));

                Map<Long, List<String>> effectiveRecipients = recipientService.effectiveFor(siteIds);

                Map<String, Set<Long>> recipientToSites = new LinkedHashMap<>();
                for (Long siteId : siteIds) {
                    List<String> recipients = effectiveRecipients.getOrDefault(siteId, List.of());
                    if (recipients.isEmpty()) {
                        log.warn("Site {} hat keine konfigurierten Empfänger oder Fallback-Empfänger für Digest im Scope {}", siteId, scope);
                    } else {
                        for (String recipient : recipients) {
                            recipientToSites.computeIfAbsent(recipient, r -> new LinkedHashSet<>()).add(siteId);
                        }
                    }
                }

                for (Map.Entry<String, Set<Long>> entry : recipientToSites.entrySet()) {
                    String recipient = entry.getKey();
                    Set<Long> theirSites = entry.getValue();
                    Digest theirs = digest.restrictedTo(theirSites);
                    if (theirs.notifiable()) {
                        OutboundMail mail = renderer.render(recipient, theirs, appSettings.baseUrl(), Locale.GERMAN);
                        outbox.enqueue(mail);
                        enqueuedCount++;
                    }
                }

                if (digest.notifiable() && appSettings.webhookEnabled() && !appSettings.webhookUrl().isBlank()) {
                    webhookNotifier.sendDigestNotification(digest, appSettings.webhookUrl(), appSettings.baseUrl());
                }

                runService.markDigested(window.runIds(), now);
            }
        }
        return enqueuedCount;
    }
}
