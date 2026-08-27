package dev.hendrikhoemberg.webtesthelper.catalog;

import dev.hendrikhoemberg.webtesthelper.catalog.persistence.CredentialEntity;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.CredentialRepository;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.SiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

@Service
@Transactional
public class CredentialService {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{0,31}$");

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
