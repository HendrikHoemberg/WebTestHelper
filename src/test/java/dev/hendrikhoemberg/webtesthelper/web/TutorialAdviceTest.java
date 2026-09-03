package dev.hendrikhoemberg.webtesthelper.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TutorialAdviceTest {

    @Test
    void unauthenticatedYieldsFalse() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AppUserService> provider = mock(ObjectProvider.class);
        TutorialAdvice advice = new TutorialAdvice(provider);
        Model model = new ConcurrentModel();

        advice.tutorialModel(model, null, null);
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

        advice.tutorialModel(model, principal, null);
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

        advice.tutorialModel(model, principal, null);
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

        advice.tutorialModel(model, principal, "start");
        assertThat(model.getAttribute("tutorialOffen")).isEqualTo(true);
    }
}
