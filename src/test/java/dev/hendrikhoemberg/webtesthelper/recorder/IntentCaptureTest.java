package dev.hendrikhoemberg.webtesthelper.recorder;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.hendrikhoemberg.webtesthelper.recorder.CapturedEvent.EventKind;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("browser")
class IntentCaptureTest {

    private static Playwright playwright;
    private static Browser browser;
    private static BrowserContext context;
    private static Page page;
    private static IntentCapture capture;

    @BeforeAll
    static void setUp() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
        context = browser.newContext();
        capture = IntentCapture.install(context);
        page = context.newPage();
    }

    @AfterAll
    static void tearDown() {
        if (context != null) {
            context.close();
        }
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @Test
    void clickingButtonReportsClickWithAllAttributes() {
        page.navigate("data:text/html,<!DOCTYPE html><html><body>"
                + "<button id='btn-submit' data-testid='test-btn-submit' role='button' aria-label='Submit Form'>Absenden</button>"
                + "</body></html>");

        capture.drain();
        page.locator("#btn-submit").click();

        List<CapturedEvent> events = capture.drain();
        assertThat(events).hasSize(1);

        CapturedEvent event = events.getFirst();
        assertThat(event.kind()).isEqualTo(EventKind.CLICK);
        assertThat(event.tagName()).isEqualTo("button");
        assertThat(event.id()).isEqualTo("btn-submit");
        assertThat(event.testId()).isEqualTo("test-btn-submit");
        assertThat(event.role()).isEqualTo("button");
        assertThat(event.accessibleName()).isEqualTo("Submit Form");
        assertThat(event.textContent()).isEqualTo("Absenden");
        assertThat(event.cssPath()).isNotBlank();
        assertThat(page.locator(event.cssPath()).count()).isEqualTo(1);
    }

    @Test
    void typingIntoInputReportsInputWithValue() {
        page.navigate("data:text/html,<!DOCTYPE html><html><body>"
                + "<label for='user-email'>E-Mail-Adresse</label>"
                + "<input id='user-email' type='email' />"
                + "</body></html>");

        capture.drain();
        page.locator("#user-email").fill("alice@example.com");

        List<CapturedEvent> events = capture.drain();
        assertThat(events).isNotEmpty();

        CapturedEvent lastEvent = events.getLast();
        assertThat(lastEvent.kind()).isEqualTo(EventKind.INPUT);
        assertThat(lastEvent.tagName()).isEqualTo("input");
        assertThat(lastEvent.id()).isEqualTo("user-email");
        assertThat(lastEvent.value()).isEqualTo("alice@example.com");
        assertThat(lastEvent.labelText()).isEqualTo("E-Mail-Adresse");
        assertThat(lastEvent.cssPath()).isNotBlank();
    }

    @Test
    void selectingOptionReportsChange() {
        page.navigate("data:text/html,<!DOCTYPE html><html><body>"
                + "<label for='country-select'>Land</label>"
                + "<select id='country-select'>"
                + "<option value='de'>Deutschland</option>"
                + "<option value='at'>Österreich</option>"
                + "</select>"
                + "</body></html>");

        capture.drain();
        page.locator("#country-select").selectOption("at");

        List<CapturedEvent> events = capture.drain();
        assertThat(events).isNotEmpty();

        CapturedEvent event = events.stream()
                .filter(e -> e.kind() == EventKind.CHANGE)
                .findFirst()
                .orElseThrow();
        assertThat(event.tagName()).isEqualTo("select");
        assertThat(event.id()).isEqualTo("country-select");
        assertThat(event.value()).isEqualTo("at");
        assertThat(event.labelText()).isEqualTo("Land");
    }

    @Test
    void submittingFormReportsSubmit() {
        page.navigate("data:text/html,<!DOCTYPE html><html><body>"
                + "<form id='contact-form' onsubmit='event.preventDefault();'>"
                + "<input type='text' name='name' value='Bob'/>"
                + "<button type='submit' id='sub-btn'>Abschicken</button>"
                + "</form>"
                + "</body></html>");

        capture.drain();
        page.locator("#sub-btn").click();

        List<CapturedEvent> events = capture.drain();
        assertThat(events).isNotEmpty();

        CapturedEvent submitEvent = events.stream()
                .filter(e -> e.kind() == EventKind.SUBMIT)
                .findFirst()
                .orElseThrow();
        assertThat(submitEvent.tagName()).isEqualTo("form");
        assertThat(submitEvent.id()).isEqualTo("contact-form");
    }

    @Test
    void clickingChildOfButtonReportsButtonElement() {
        page.navigate("data:text/html,<!DOCTYPE html><html><body>"
                + "<button id='icon-btn' data-testid='icon-button'>"
                + "<span id='child-icon'>★</span>"
                + "<span id='child-text'>Favorit</span>"
                + "</button>"
                + "</body></html>");

        capture.drain();
        page.locator("#child-icon").click();

        List<CapturedEvent> events = capture.drain();
        assertThat(events).hasSize(1);

        CapturedEvent event = events.getFirst();
        assertThat(event.kind()).isEqualTo(EventKind.CLICK);
        assertThat(event.tagName()).isEqualTo("button");
        assertThat(event.id()).isEqualTo("icon-btn");
        assertThat(event.testId()).isEqualTo("icon-button");
        assertThat(event.textContent()).contains("Favorit");
    }

    @Test
    void bindingSurvivesNavigation() {
        page.navigate("data:text/html,<!DOCTYPE html><html><body>"
                + "<button id='page1-btn'>Seite 1</button>"
                + "</body></html>");
        capture.drain();
        page.locator("#page1-btn").click();

        page.navigate("data:text/html,<!DOCTYPE html><html><body>"
                + "<button id='page2-btn'>Seite 2</button>"
                + "</body></html>");
        page.locator("#page2-btn").click();

        List<CapturedEvent> events = capture.drain();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).id()).isEqualTo("page1-btn");
        assertThat(events.get(0).textContent()).isEqualTo("Seite 1");
        assertThat(events.get(1).id()).isEqualTo("page2-btn");
        assertThat(events.get(1).textContent()).isEqualTo("Seite 2");
    }

    @Test
    void drainClearsEvents() {
        page.navigate("data:text/html,<!DOCTYPE html><html><body>"
                + "<button id='drain-btn'>Drain</button>"
                + "</body></html>");
        capture.drain();
        page.locator("#drain-btn").click();

        List<CapturedEvent> firstDrain = capture.drain();
        assertThat(firstDrain).hasSize(1);

        List<CapturedEvent> secondDrain = capture.drain();
        assertThat(secondDrain).isEmpty();
    }

    @Test
    void dataCyAndDataTestCapturedAsTestId() {
        page.navigate("data:text/html,<!DOCTYPE html><html><body>"
                + "<button id='cy-btn' data-cy='cypress-btn'>Cy</button>"
                + "<button id='dt-btn' data-test='test-btn'>DT</button>"
                + "</body></html>");

        capture.drain();
        page.locator("#cy-btn").click();
        page.locator("#dt-btn").click();

        List<CapturedEvent> events = capture.drain();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).testId()).isEqualTo("cypress-btn");
        assertThat(events.get(1).testId()).isEqualTo("test-btn");
    }

    @Test
    void ariaLabelledByCapturedAsAccessibleName() {
        page.navigate("data:text/html,<!DOCTYPE html><html><body>"
                + "<span id='first-label'>Vorname</span> "
                + "<span id='last-label'>Nachname</span> "
                + "<button id='labelledby-btn' aria-labelledby='first-label last-label'>Klick</button>"
                + "</body></html>");

        capture.drain();
        page.locator("#labelledby-btn").click();

        List<CapturedEvent> events = capture.drain();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().accessibleName()).isEqualTo("Vorname Nachname");
    }

    @Test
    void linkWithHrefReportsRoleLink() {
        page.navigate("data:text/html,<!DOCTYPE html><html><body>"
                + "<a id='nav-link' href='/ziel.html'>Zur Zielseite</a>"
                + "</body></html>");

        capture.drain();
        page.locator("#nav-link").click();

        List<CapturedEvent> events = capture.drain();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().role()).isEqualTo("link");
        assertThat(events.getFirst().textContent()).isEqualTo("Zur Zielseite");
    }

    @Test
    void textareaInputReportsInputWithValue() {
        page.navigate("data:text/html,<!DOCTYPE html><html><body>"
                + "<textarea id='message-box'></textarea>"
                + "</body></html>");

        capture.drain();
        page.locator("#message-box").fill("Hallo Welt");

        List<CapturedEvent> events = capture.drain();
        assertThat(events).isNotEmpty();
        CapturedEvent lastEvent = events.getLast();
        assertThat(lastEvent.kind()).isEqualTo(EventKind.INPUT);
        assertThat(lastEvent.tagName()).isEqualTo("textarea");
        assertThat(lastEvent.value()).isEqualTo("Hallo Welt");
    }
}
