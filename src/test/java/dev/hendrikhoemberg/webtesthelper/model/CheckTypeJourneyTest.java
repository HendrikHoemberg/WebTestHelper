package dev.hendrikhoemberg.webtesthelper.model;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 6.2, 10.2, Roadmap D108:
 * JOURNEY_STEP_FAILED and SELECTOR_DRIFT are findings, but journeys are not crawl/interaction checks.
 * CheckType.journey() is true for exactly these two constants and false for all others.
 * A check type is at most one kind: journey() and interaction() are mutually exclusive.
 */
class CheckTypeJourneyTest {

    @Test
    void journeyIsTrueForExactlyTheTwoNewConstants() {
        Set<CheckType> journeyTypes = EnumSet.allOf(CheckType.class).stream()
                .filter(CheckType::journey)
                .collect(Collectors.toSet());

        assertThat(journeyTypes).containsExactlyInAnyOrder(
                CheckType.JOURNEY_STEP_FAILED,
                CheckType.SELECTOR_DRIFT
        );
    }

    @Test
    void journeyIsFalseForAllSeventeenOtherConstants() {
        Set<CheckType> nonJourneyTypes = EnumSet.allOf(CheckType.class).stream()
                .filter(type -> !type.journey())
                .collect(Collectors.toSet());

        assertThat(nonJourneyTypes).hasSize(17);
        assertThat(CheckType.PAGE_STATUS.journey()).isFalse();
        assertThat(CheckType.PAGE_UNREACHABLE.journey()).isFalse();
        assertThat(CheckType.DEAD_LINK.journey()).isFalse();
        assertThat(CheckType.REDIRECT_CHAIN.journey()).isFalse();
        assertThat(CheckType.IMAGE_BROKEN.journey()).isFalse();
        assertThat(CheckType.FILE_DOWNLOAD.journey()).isFalse();
        assertThat(CheckType.MEDIA_PLAYABLE.journey()).isFalse();
        assertThat(CheckType.IFRAME_EMBED.journey()).isFalse();
        assertThat(CheckType.MIXED_CONTENT.journey()).isFalse();
        assertThat(CheckType.CONSOLE_ERRORS.journey()).isFalse();
        assertThat(CheckType.TLS_CERT.journey()).isFalse();
        assertThat(CheckType.HREFLANG.journey()).isFalse();
        assertThat(CheckType.SITEMAP_CONSISTENCY.journey()).isFalse();
        assertThat(CheckType.COOKIE_BANNER.journey()).isFalse();
        assertThat(CheckType.LANGUAGE_SWITCHER.journey()).isFalse();
        assertThat(CheckType.BUTTON_REACHABILITY.journey()).isFalse();
        assertThat(CheckType.CONTACT_FORM.journey()).isFalse();
    }

    @Test
    void interactionIsFalseForBothJourneyConstants() {
        assertThat(CheckType.JOURNEY_STEP_FAILED.interaction()).isFalse();
        assertThat(CheckType.SELECTOR_DRIFT.interaction()).isFalse();
    }

    @Test
    void aTypeIsAtMostOneKind() {
        for (CheckType type : CheckType.values()) {
            assertThat(type.journey() && type.interaction())
                    .as("CheckType %s cannot be both journey and interaction", type)
                    .isFalse();
        }
    }
}
