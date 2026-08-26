package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;

import java.util.List;

/**
 * One line of the setup proposal: a {@link CheckType}, whether the probe suggests ticking it, and
 * the plain-language reason for that suggestion as a message key plus its arguments — so
 * §13.3's *"Kontaktformular auf /kontakt gefunden"* is one sentence in the properties file and
 * not string concatenation in a template (§13.1).
 *
 * @param type        the check the line offers
 * @param suggested   whether the probe fell on the side of ticking it
 * @param reasonKey   a {@code ui.einrichtung.grund.*} message key
 * @param reasonArgs  the {@code {0}}, {@code {1}} … placeholders for {@code reasonKey}
 */
public record CheckProposal(CheckType type, boolean suggested, String reasonKey, List<String> reasonArgs) {

    public CheckProposal {
        reasonArgs = List.copyOf(reasonArgs);
    }
}
