package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.auth.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TutorialAdviceTest {

    @Test
    void unauthenticatedYieldsFalse() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AppUserService> provider = mock(ObjectProvider.class);
        TutorialAdvice advice = new TutorialAdvice(provider);
        Model model = new ConcurrentModel();

        advice.tutorialModel(model, null, null, null);
        assertThat(model.getAttribute("tutorialOffen")).isEqualTo(false);
    }

    @Test
    void authenticatedIncompleteYieldsTrue() {
        AppUserService service = mock(AppUserService.class);
        when(service.isTutorialAbgeschlossen("anna")).thenReturn(false);

        @SuppressWarnings("unchecked")
        ObjectProvider<AppUserService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);

        TutorialAdvice advice = new TutorialAdvice(provider);
        Model model = new ConcurrentModel();
        Principal principal = () -> "anna";

        advice.tutorialModel(model, principal, null, null);
        assertThat(model.getAttribute("tutorialOffen")).isEqualTo(true);
    }

    @Test
    void authenticatedCompletedYieldsFalse() {
        AppUserService service = mock(AppUserService.class);
        when(service.isTutorialAbgeschlossen("anna")).thenReturn(true);

        @SuppressWarnings("unchecked")
        ObjectProvider<AppUserService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);

        TutorialAdvice advice = new TutorialAdvice(provider);
        Model model = new ConcurrentModel();
        Principal principal = () -> "anna";

        advice.tutorialModel(model, principal, null, null);
        assertThat(model.getAttribute("tutorialOffen")).isEqualTo(false);
    }

    @Test
    void tourStartParamOverridesCompletedStatus() {
        AppUserService service = mock(AppUserService.class);
        when(service.isTutorialAbgeschlossen("anna")).thenReturn(true);

        @SuppressWarnings("unchecked")
        ObjectProvider<AppUserService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);

        TutorialAdvice advice = new TutorialAdvice(provider);
        Model model = new ConcurrentModel();
        Principal principal = () -> "anna";

        advice.tutorialModel(model, principal, "start", null);
        assertThat(model.getAttribute("tutorialOffen")).isEqualTo(true);
    }

    @Test
    void tutorialStatusIsCachedPerSession() {
        AppUserService service = mock(AppUserService.class);
        when(service.isTutorialAbgeschlossen("anna")).thenReturn(false);

        @SuppressWarnings("unchecked")
        ObjectProvider<AppUserService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);

        TutorialAdvice advice = new TutorialAdvice(provider);
        Principal principal = () -> "anna";
        MockHttpSession session = new MockHttpSession();

        Model first = new ConcurrentModel();
        advice.tutorialModel(first, principal, null, session);
        assertThat(first.getAttribute("tutorialOffen")).isEqualTo(true);

        Model second = new ConcurrentModel();
        advice.tutorialModel(second, principal, null, session);
        assertThat(second.getAttribute("tutorialOffen")).isEqualTo(true);

        verify(service, times(1)).isTutorialAbgeschlossen("anna");
    }

    @Test
    void aFreshSessionQueriesTheServiceAgain() {
        AppUserService service = mock(AppUserService.class);
        when(service.isTutorialAbgeschlossen("anna")).thenReturn(false);

        @SuppressWarnings("unchecked")
        ObjectProvider<AppUserService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);

        TutorialAdvice advice = new TutorialAdvice(provider);
        Principal principal = () -> "anna";

        advice.tutorialModel(new ConcurrentModel(), principal, null, new MockHttpSession());
        advice.tutorialModel(new ConcurrentModel(), principal, null, new MockHttpSession());

        verify(service, times(2)).isTutorialAbgeschlossen("anna");
    }

    @Test
    void tourStartParamUpdatesTheCachedSessionValue() {
        AppUserService service = mock(AppUserService.class);
        when(service.isTutorialAbgeschlossen("anna")).thenReturn(true);

        @SuppressWarnings("unchecked")
        ObjectProvider<AppUserService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);

        TutorialAdvice advice = new TutorialAdvice(provider);
        Principal principal = () -> "anna";
        MockHttpSession session = new MockHttpSession();

        Model first = new ConcurrentModel();
        advice.tutorialModel(first, principal, null, session);
        assertThat(first.getAttribute("tutorialOffen")).isEqualTo(false);

        Model second = new ConcurrentModel();
        advice.tutorialModel(second, principal, "start", session);
        assertThat(second.getAttribute("tutorialOffen")).isEqualTo(true);

        Model third = new ConcurrentModel();
        advice.tutorialModel(third, principal, null, session);
        assertThat(third.getAttribute("tutorialOffen")).isEqualTo(true);

        verify(service, times(1)).isTutorialAbgeschlossen("anna");
    }
}
