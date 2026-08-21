package dev.hendrikhoemberg.webtesthelper.catalog;

import dev.hendrikhoemberg.webtesthelper.catalog.persistence.SiteCheckSettingEntity;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.SiteCheckSettingRepository;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.SiteEntity;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.SiteRepository;
import dev.hendrikhoemberg.webtesthelper.model.CheckSetting;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The site catalog. Normalises base URLs, seeds one check setting per {@link CheckType}
 * and hands the runner an immutable {@link SiteContext}. The JPA entities never leave
 * this module (spec 5.1).
 */
@Service
@Transactional
public class SiteService {

    private static final Set<CheckType> NOISY_BY_DEFAULT = EnumSet.of(
            CheckType.CONSOLE_ERRORS, CheckType.SITEMAP_CONSISTENCY);

    private final SiteRepository sites;
    private final SiteCheckSettingRepository checkSettings;

    public SiteService(SiteRepository sites, SiteCheckSettingRepository checkSettings) {
        this.sites = sites;
        this.checkSettings = checkSettings;
    }

    public long create(SiteForm form) {
        String rawBaseUrl = form.baseUrl();
        NormalizedUrl normalized = UrlNormalizer.normalize(rawBaseUrl)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Nicht als URL interpretierbar: " + rawBaseUrl));

        SiteEntity site = new SiteEntity();
        applyForm(site, form, normalized);
        site.setPinnedKeyPages(List.of());
        SiteEntity saved = sites.save(site);

        for (CheckType type : CheckType.values()) {
            SiteCheckSettingEntity setting = new SiteCheckSettingEntity();
            setting.setSiteId(saved.getId());
            setting.setCheckType(type);
            setting.setEnabled(!NOISY_BY_DEFAULT.contains(type));
            setting.setConfig(Map.of());
            checkSettings.save(setting);
        }
        return saved.getId();
    }

    public void update(long id, SiteForm form) {
        SiteEntity site = requireSite(id);
        NormalizedUrl normalized = UrlNormalizer.normalize(form.baseUrl())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Nicht als URL interpretierbar: " + form.baseUrl()));
        applyForm(site, form, normalized);
        site.setUpdatedAt(Instant.now());
        sites.save(site);
    }

    public void delete(long id) {
        sites.deleteById(id);
    }

    public SiteContext contextFor(long siteId) {
        SiteEntity site = requireSite(siteId);
        List<SiteCheckSettingEntity> settings = checkSettings.findBySiteId(siteId);
        Map<CheckType, CheckSetting> checkSettingsMap = new EnumMap<>(CheckType.class);
        for (SiteCheckSettingEntity setting : settings) {
            checkSettingsMap.put(setting.getCheckType(),
                    new CheckSetting(setting.isEnabled(), setting.getSeverityOverride(), setting.getConfig()));
        }
        return new SiteContext(
                siteId,
                site.getName(),
                UrlNormalizer.normalize(site.getBaseUrl()).orElseThrow(),
                new CrawlBudget(site.getMaxPages(), site.getMaxDepth(),
                        Duration.ofSeconds(site.getMaxDurationSeconds())),
                site.getIncludePatterns(),
                site.getExcludePatterns(),
                site.getPinnedKeyPages(),
                site.isRespectRobots(),
                site.getUserAgent(),
                checkSettingsMap);
    }

    public void setCheckEnabled(long siteId, CheckType type, boolean enabled) {
        requireSite(siteId);
        SiteCheckSettingEntity setting = checkSettings.findBySiteIdAndCheckType(siteId, type)
                .orElseGet(() -> newSetting(siteId, type));
        setting.setEnabled(enabled);
        // A concurrent insert of the same (site_id, check_type) would violate ux_site_check and
        // roll back this transaction — accepted rather than retried, because a conflict here is
        // vanishingly rare (settings are seeded for every CheckType at create()).
        checkSettings.save(setting);
    }

    public List<SiteSummary> summaries() {
        List<SiteEntity> sites = this.sites.findAll();
        Map<Long, Integer> settingCounts = new java.util.HashMap<>();
        for (SiteCheckSettingEntity setting : checkSettings.findBySiteIdIn(
                sites.stream().map(SiteEntity::getId).toList())) {
            settingCounts.merge(setting.getSiteId(), 1, Integer::sum);
        }
        return sites.stream()
                .map(site -> new SiteSummary(site.getId(), site.getName(), site.getBaseUrl(),
                        site.isEnabled(), settingCounts.getOrDefault(site.getId(), 0)))
                .toList();
    }

    public SiteSummary summary(long id) {
        SiteEntity site = requireSite(id);
        return new SiteSummary(site.getId(), site.getName(), site.getBaseUrl(), site.isEnabled(),
                checkSettings.findBySiteId(id).size());
    }

    public boolean exists(long siteId) {
        return sites.existsById(siteId);
    }

    private SiteCheckSettingEntity newSetting(long siteId, CheckType type) {
        SiteCheckSettingEntity setting = new SiteCheckSettingEntity();
        setting.setSiteId(siteId);
        setting.setCheckType(type);
        setting.setEnabled(!NOISY_BY_DEFAULT.contains(type));
        setting.setConfig(Map.of());
        return setting;
    }

    private void applyForm(SiteEntity site, SiteForm form, NormalizedUrl normalized) {
        site.setName(form.name());
        site.setBaseUrl(normalized.value());
        site.setMaxPages(form.maxPages());
        site.setMaxDepth(form.maxDepth());
        site.setMaxDurationSeconds((int) form.maxDuration().toSeconds());
        site.setIncludePatterns(form.includePatterns() == null ? List.of() : form.includePatterns());
        site.setExcludePatterns(form.excludePatterns() == null ? List.of() : form.excludePatterns());
        site.setRespectRobots(form.respectRobots());
        site.setUserAgent(form.userAgent());
    }

    private SiteEntity requireSite(long id) {
        return sites.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Site existiert nicht: " + id));
    }
}
