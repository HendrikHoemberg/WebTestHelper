package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.checks.Clickables.Clickable;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What survives selection: the JavaScript-driven controls the crawl cannot see
 * (plan 11, Task 3). Sibling of {@link ClickablesExclusionsTest}; see its javadoc for why
 * neither is {@code @Nested}.
 */
class ClickablesKeepersTest {

    private static final NormalizedUrl BASE = Snapshots.url("https://example.com/start");

    @Test
    void plainButtonIsKept() {
        List<Clickable> harvested = List.of(
                new Clickable(0, "button", "button", "Mehr erfahren", null, false, false, true, null)
        );

        List<Clickable> selected = Clickables.select(harvested, BASE, 10);

        assertThat(selected).containsExactly(
                new Clickable(0, "button", "button", "Mehr erfahren", null, false, false, true, null)
        );
    }

    @Test
    void anchorWithHashHrefIsKept() {
        List<Clickable> harvested = List.of(
                new Clickable(0, "a", null, "Menü öffnen", "#", false, false, true, null)
        );

        List<Clickable> selected = Clickables.select(harvested, BASE, 10);

        assertThat(selected).containsExactly(
                new Clickable(0, "a", null, "Menü öffnen", "#", false, false, true, null)
        );
    }

    @Test
    void anchorWithJavascriptHrefIsKept() {
        List<Clickable> harvested = List.of(
                new Clickable(0, "a", null, "Details anzeigen", "javascript:void(0)", false, false, true, null)
        );

        List<Clickable> selected = Clickables.select(harvested, BASE, 10);

        assertThat(selected).containsExactly(
                new Clickable(0, "a", null, "Details anzeigen", "javascript:void(0)", false, false, true, null)
        );
    }

    @Test
    void anchorResolvingToCurrentPageIsKept() {
        List<Clickable> harvested = List.of(
                new Clickable(0, "a", null, "Aktualisieren", "/start", false, false, true, null),
                new Clickable(1, "a", null, "Abschnitt", "https://example.com/start#details", false, false, true, null)
        );

        List<Clickable> selected = Clickables.select(harvested, BASE, 10);

        assertThat(selected).hasSize(2);
    }

    @Test
    void anchorWithNoOrEmptyHrefIsKept() {
        List<Clickable> harvested = List.of(
                new Clickable(0, "a", null, "Akkordeon", null, false, false, true, null),
                new Clickable(1, "a", null, "Filter", "", false, false, true, null)
        );

        List<Clickable> selected = Clickables.select(harvested, BASE, 10);

        assertThat(selected).hasSize(2);
    }
}
