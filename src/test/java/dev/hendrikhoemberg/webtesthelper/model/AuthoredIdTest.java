package dev.hendrikhoemberg.webtesthelper.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoredIdTest {

    @ParameterizedTest(name = "id \"{0}\" -> looksAuthored: {1}")
    @CsvSource({
            // §10.2 examples
            ":r1:, false",
            "ember123, false",
            "a3f9b2c81d, false",
            "id-4815162342, false",

            // Explicitly requested examples in brief / roadmap
            "kontakt-formular, true",
            "absenden, true",
            ":r7:, false",
            "x1, true",

            // Real IDs from fixture site
            "tut-nichts, true",
            "kontaktformular, true",
            "name, true",
            "email, true",
            "nachricht, true",
            "zaehler, true",
            "ton, true",
            "film, true",
            "reise-tief-link, true",
            "reise-form, true",
            "reise-name, true",
            "reise-ziel, true",
            "reise-submit, true",
            "spaet-ziel, true",
            "bestaetigung-titel, true",
            "reise-start-link, true",
            "cookie-hinweis, true",
            "ziel-fehlt, true",
            "ziel-ok, true",
            "rueckrufformular, true",
            "telefon, true",
            "submit-btn, true",
            "website, true",
            "fax, true",
            "url2, true",
            "company2, true",
            "anrede, true",
            "betreff, true",
            "datenschutz, true",

            // Generator prefixes
            "ember, false",
            "ember-123, false",
            "ember456, false",
            "react-, false",
            "react-1, false",
            "react-select-2, false",
            "ng-, false",
            "ng-tns-c1-0, false",
            "ng-star-inserted, false",
            "mui-, false",
            "mui-1234, false",
            "radix-, false",
            "radix-id-123, false",
            "headlessui-, false",
            "headlessui-dialog-1, false",
            "svelte-, false",
            "svelte-xyz, false",

            // Case-insensitive generator prefixes
            "EMBER123, false",
            "React-123, false",
            "NG-component, false",
            "Mui-root, false",
            "Radix-dropdown, false",
            "Headlessui-popover, false",
            "Svelte-box, false",

            // Long hex matches (>= 8 chars)
            "deadbeef, false",
            "DEADBEEF, false",
            "c0ffee12, false",
            "12345678, false",
            "0123456789abcdef, false",

            // Hex edge cases (short hex or non-hex)
            "deadbee, true",        // 7 chars hex, 0 digits -> accepted
            "deadbeeg, true",       // 8 chars with non-hex 'g', 0 digits -> accepted

            // Digit ratio heuristic
            "1, false",             // 1/1 digits (> half) -> rejected
            "12, false",            // 2/2 digits (> half) -> rejected
            "x12, false",           // 2/3 digits (> half) -> rejected
            "btn-1, true",          // 1/5 digits (<= half) -> accepted
            "btn-12, true",         // 2/6 digits (<= half) -> accepted
            "btn-123, true",        // 3/7 digits (<= half) -> accepted
            "btn-12345, false",     // 5/9 digits (> half) -> rejected

            // Characters outside [A-Za-z0-9_-]
            "data-v.1, false",
            "foo.bar, false",
            "foo/bar, false",
            "foo:bar, false",
            "foo#bar, false",
            "foo@bar, false",
            "foo bar, false",
            "uebel-ä, false",

            // Standard authored identifiers
            "user_name, true",
            "header-main_nav, true",
            "step_2_proceed, true"
    })
    void looksAuthoredClassifiesIdentifiersCorrectly(String id, boolean expected) {
        assertThat(AuthoredId.looksAuthored(id)).isEqualTo(expected);
    }

    @Test
    void rejectsNullAndBlank() {
        assertThat(AuthoredId.looksAuthored(null)).isFalse();
        assertThat(AuthoredId.looksAuthored("")).isFalse();
        assertThat(AuthoredId.looksAuthored("   ")).isFalse();
        assertThat(AuthoredId.looksAuthored("\t\n")).isFalse();
    }

    @Test
    void acceptsMaxLength64AndRejectsLength65() {
        String len64 = "g".repeat(64);
        String len65 = "g".repeat(65);

        assertThat(AuthoredId.looksAuthored(len64)).isTrue();
        assertThat(AuthoredId.looksAuthored(len65)).isFalse();
    }
}
