package dev.hendrikhoemberg.webtesthelper.catalog;

import dev.hendrikhoemberg.webtesthelper.catalog.persistence.NotificationRecipientEntity;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.NotificationRecipientRepository;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.SiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service managing notification recipients per site with global fallback (spec 11.2, D59).
 * Ensures JPA entities remain inside persistence package and returns domain records.
 */
@Service
@Transactional
public class RecipientService {

    private final NotificationRecipientRepository recipients;
    private final SiteRepository sites;
    private final AppSettings appSettings;

    public RecipientService(
            NotificationRecipientRepository recipients,
            SiteRepository sites,
            AppSettings appSettings) {
        this.recipients = recipients;
        this.sites = sites;
        this.appSettings = appSettings;
    }

    @Transactional(readOnly = true)
    public List<Recipient> list(long siteId) {
        return recipients.findBySiteIdOrderByEmailAsc(siteId).stream()
                .map(this::toRecipient)
                .toList();
    }

    public long add(long siteId, String email) {
        if (!sites.existsById(siteId)) {
            throw new IllegalArgumentException("Site existiert nicht: " + siteId);
        }
        if (!EmailAddresses.isValid(email)) {
            throw new IllegalArgumentException("recipient.email.invalid");
        }
        String normalized = EmailAddresses.normalize(email);
        if (recipients.existsBySiteIdAndEmail(siteId, normalized)) {
            throw new IllegalArgumentException("recipient.email.duplicate");
        }

        NotificationRecipientEntity entity = new NotificationRecipientEntity();
        entity.setSiteId(siteId);
        entity.setEmail(normalized);
        NotificationRecipientEntity saved = recipients.save(entity);
        return saved.getId();
    }

    public void remove(long siteId, long recipientId) {
        recipients.findById(recipientId).ifPresent(entity -> {
            if (entity.getSiteId() != null && entity.getSiteId().equals(siteId)) {
                recipients.delete(entity);
            }
        });
    }

    @Transactional(readOnly = true)
    public Map<Long, List<String>> effectiveFor(Collection<Long> siteIds) {
        if (siteIds == null || siteIds.isEmpty()) {
            return Map.of();
        }
        List<NotificationRecipientEntity> rows = recipients.findBySiteIdInOrderByEmailAsc(siteIds);
        Map<Long, List<String>> bySite = new HashMap<>();
        for (NotificationRecipientEntity row : rows) {
            bySite.computeIfAbsent(row.getSiteId(), k -> new ArrayList<>()).add(row.getEmail());
        }
        List<String> fallback = appSettings.fallbackRecipients();
        Map<Long, List<String>> result = new LinkedHashMap<>();
        for (Long siteId : siteIds) {
            List<String> siteRecipients = bySite.get(siteId);
            if (siteRecipients != null && !siteRecipients.isEmpty()) {
                result.put(siteId, List.copyOf(siteRecipients));
            } else if (!fallback.isEmpty()) {
                result.put(siteId, fallback);
            } else {
                result.put(siteId, List.of());
            }
        }
        return result;
    }

    private Recipient toRecipient(NotificationRecipientEntity entity) {
        return new Recipient(entity.getId(), entity.getSiteId(), entity.getEmail());
    }
}
