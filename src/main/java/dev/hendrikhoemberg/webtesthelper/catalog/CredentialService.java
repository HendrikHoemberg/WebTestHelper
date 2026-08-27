package dev.hendrikhoemberg.webtesthelper.catalog;

import dev.hendrikhoemberg.webtesthelper.catalog.persistence.CredentialEntity;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.CredentialRepository;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.SiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Transactional
public class CredentialService {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{0,31}$");
    private static final Pattern CANDIDATE_REF_PATTERN = Pattern.compile("\\{\\{[^{}]*\\}\\}");


    private final CredentialRepository credentials;
    private final SiteRepository sites;
    private final SecretBox secretBox;

    public CredentialService(
            CredentialRepository credentials,
            SiteRepository sites,
            SecretBox secretBox) {
        this.credentials = credentials;
        this.sites = sites;
        this.secretBox = secretBox;
    }

    public long create(long siteId, String name, String username, String password) {
        if (!sites.existsById(siteId)) {
            throw new IllegalArgumentException("Site existiert nicht: " + siteId);
        }
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("credential.name.invalid");
        }
        if (credentials.existsBySiteIdAndName(siteId, name)) {
            throw new IllegalArgumentException("credential.name.duplicate");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("credential.password.blank");
        }

        CredentialEntity entity = new CredentialEntity();
        entity.setSiteId(siteId);
        entity.setName(name);
        entity.setUsername(username);
        entity.setSecret(secretBox.encrypt(password));
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());

        CredentialEntity saved = credentials.save(entity);
        return saved.getId();
    }

    public void update(long siteId, long credentialId, String username, String password) {
        credentials.findById(credentialId).ifPresent(entity -> {
            if (entity.getSiteId() != null && entity.getSiteId().equals(siteId)) {
                entity.setUsername(username);
                if (password != null && !password.isBlank()) {
                    entity.setSecret(secretBox.encrypt(password));
                }
                entity.setUpdatedAt(Instant.now());
                credentials.save(entity);
            }
        });
    }

    public void delete(long siteId, long credentialId) {
        credentials.findById(credentialId).ifPresent(entity -> {
            if (entity.getSiteId() != null && entity.getSiteId().equals(siteId)) {
                credentials.delete(entity);
            }
        });
    }

    @Transactional(readOnly = true)
    public List<Credential> list(long siteId) {
        return credentials.findBySiteIdOrderByNameAsc(siteId).stream()
                .map(this::toCredential)
                .toList();
    }

    @Transactional(readOnly = true)
    public SecretText resolve(long siteId, String template) {
        if (template == null) {
            return SecretText.plain(null);
        }

        Matcher candidateMatcher = CANDIDATE_REF_PATTERN.matcher(template);
        while (candidateMatcher.find()) {
            String candidate = candidateMatcher.group();
            if (candidate.toLowerCase().contains("cred") && !CredentialReference.PATTERN.matcher(candidate).matches()) {
                throw new IllegalArgumentException("credential.reference.malformed: " + candidate);
            }
        }

        List<CredentialReference> refs = CredentialReference.findAll(template);
        if (refs.isEmpty()) {
            return SecretText.plain(template);
        }

        Matcher matcher = CredentialReference.PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            CredentialField field = CredentialField.parse(matcher.group(2)).orElseThrow();
            CredentialReference ref = new CredentialReference(name, field);

            CredentialEntity entity = credentials.findBySiteIdAndName(siteId, ref.name())
                    .orElseThrow(() -> new IllegalArgumentException("credential.reference.unknown: " + ref.token()));

            String value;
            if (ref.field() == CredentialField.USERNAME) {
                value = entity.getUsername() != null ? entity.getUsername() : "";
            } else {
                try {
                    value = secretBox.decrypt(entity.getSecret());
                } catch (RuntimeException e) {
                    throw new IllegalStateException("credential.reference.unreadable: " + ref.token(), e);
                }
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);

        return SecretText.of(sb.toString(), template);
    }


    private Credential toCredential(CredentialEntity entity) {
        boolean readable = isReadable(entity.getSecret());
        return new Credential(
                entity.getId(),
                entity.getSiteId(),
                entity.getName(),
                entity.getUsername(),
                entity.getUpdatedAt(),
                readable
        );
    }

    private boolean isReadable(String secret) {
        if (secret == null) {
            return false;
        }
        try {
            secretBox.decrypt(secret);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
