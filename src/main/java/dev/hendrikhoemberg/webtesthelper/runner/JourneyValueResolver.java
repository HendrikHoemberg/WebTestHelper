package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.catalog.CredentialService;
import dev.hendrikhoemberg.webtesthelper.catalog.SecretText;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Resolves credential templates and plain text in journey steps (§10.3).
 *
 * <p>Delegates credential references to {@link CredentialService#resolve(long, String)}
 * so runner code does not interact directly with credential repositories or crypto internals.
 */
@Component
public class JourneyValueResolver {

    private final CredentialService credentialService;

    public JourneyValueResolver(CredentialService credentialService) {
        this.credentialService = Objects.requireNonNull(credentialService, "credentialService");
    }

    /**
     * Resolves the step's value into a {@link SecretText}.
     *
     * @param siteId the site ID owning the credentials
     * @param step   the journey step containing the value template
     * @return the resolved {@link SecretText} (plain or sensitive)
     */
    public SecretText resolve(long siteId, JourneyStep step) {
        Objects.requireNonNull(step, "step");
        if (step.value() == null || step.value().isEmpty()) {
            return SecretText.plain("");
        }
        return credentialService.resolve(siteId, step.value());
    }
}
