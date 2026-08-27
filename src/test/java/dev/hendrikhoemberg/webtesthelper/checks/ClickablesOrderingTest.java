package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.checks.Clickables.Clickable;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic candidate order and truncation (plan 11, Task 3, D74): the same page must
 * yield the same candidates in the same order next week. Sibling of
 * {@link ClickablesExclusionsTest}; see its javadoc for why neither is {@code @Nested}.
 */
class ClickablesOrderingTest {

    private static final NormalizedUrl BASE = Snapshots.url("https://example.com/start");

    @Test
    void maxTruncatesInDocumentOrder() {
        List<Clickable> harvested = List.of(
                new Clickable(0, "button", "button", "Btn 0", null, false, false, true, null),
                new Clickable(1, "button", "button", "Btn 1", null, false, false, true, null),
                new Clickable(2, "button", "button", "Btn 2", null, false, false, true, null),
                new Clickable(3, "button", "button", "Btn 3", null, false, false, true, null)
        );

        List<Clickable> selected = Clickables.select(harvested, BASE, 2);

        assertThat(selected).containsExactly(
                new Clickable(0, "button", "button", "Btn 0", null, false, false, true, null),
                new Clickable(1, "button", "button", "Btn 1", null, false, false, true, null)
        );
    }

    @Test
    void shuffledInputProducesSameListInDocumentOrder() {
        List<Clickable> harvested = List.of(
                new Clickable(0, "button", "button", "Erster", null, false, false, true, null),
                new Clickable(1, "button", "button", "Zweiter", null, false, false, true, null),
                new Clickable(2, "button", "button", "Dritter", null, false, false, true, null),
                new Clickable(3, "button", "button", "Vierter", null, false, false, true, null)
        );

        List<Clickable> shuffled = new ArrayList<>(harvested);
        Collections.shuffle(shuffled);

        List<Clickable> selectedOriginal = Clickables.select(harvested, BASE, 10);
        List<Clickable> selectedShuffled = Clickables.select(shuffled, BASE, 10);

        assertThat(selectedShuffled).containsExactlyElementsOf(selectedOriginal);
        assertThat(selectedShuffled).extracting(Clickable::index).isSorted();
    }

    @Test
    void deduplicationByLabelAndIndex() {
        List<Clickable> harvested = List.of(
                new Clickable(0, "button", "button", "Mehr", null, false, false, true, null),
                new Clickable(0, "button", "button", "Mehr", null, false, false, true, null),
                new Clickable(1, "button", "button", "Mehr", null, false, false, true, null)
        );

        List<Clickable> selected = Clickables.select(harvested, BASE, 10);

        assertThat(selected).containsExactly(
                new Clickable(0, "button", "button", "Mehr", null, false, false, true, null),
                new Clickable(1, "button", "button", "Mehr", null, false, false, true, null)
        );
    }

    @Test
    void nullOrEmptyOrNegativeReturnsEmpty() {
        assertThat(Clickables.select(null, BASE, 5)).isEmpty();
        assertThat(Clickables.select(List.of(), BASE, 5)).isEmpty();
        assertThat(Clickables.select(List.of(new Clickable(0, "button", "button", "Mehr", null, false, false, true, null)), null, 5)).isEmpty();
        assertThat(Clickables.select(List.of(new Clickable(0, "button", "button", "Mehr", null, false, false, true, null)), BASE, 0)).isEmpty();
        assertThat(Clickables.select(List.of(new Clickable(0, "button", "button", "Mehr", null, false, false, true, null)), BASE, -1)).isEmpty();
    }
}
