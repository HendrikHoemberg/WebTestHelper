package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.reporting.NotificationState;
import dev.hendrikhoemberg.webtesthelper.reporting.OutboxEntry;
import dev.hendrikhoemberg.webtesthelper.reporting.OutboxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(OutboxController.class)
class OutboxControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OutboxService outboxService;

    @MockitoBean
    AppUserService appUserService;

    @Test
    @WithMockUser(roles = "USER")
    void getPostausgangAsUserIsForbidden() throws Exception {
        mvc.perform(get("/postausgang"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getPostausgangAsAdminRendersRecentEntriesAndLastErrorForFailedRow() throws Exception {
        OutboxEntry entry = new OutboxEntry(
                42L,
                "boss@example.com",
                "Monatsbericht",
                NotificationState.FAILED,
                5,
                Instant.now().minusSeconds(3600),
                null,
                Instant.now().plusSeconds(60),
                "Authentication failed on mail.relay.org"
        );

        when(outboxService.recent(50)).thenReturn(List.of(entry));
        when(outboxService.failedCount()).thenReturn(1);
        when(outboxService.lastError()).thenReturn(Optional.of("Authentication failed on mail.relay.org"));

        mvc.perform(get("/postausgang"))
                .andExpect(status().isOk())
                .andExpect(view().name("postausgang/index"))
                .andExpect(model().attributeExists("eintraege"))
                .andExpect(content().string(containsString("boss@example.com")))
                .andExpect(content().string(containsString("Monatsbericht")))
                .andExpect(content().string(containsString("Authentication failed on mail.relay.org")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void healthBannerAppearsWhenFailedCountGreaterThanZeroAndAbsentAtZero() throws Exception {
        // When failedCount > 0
        when(outboxService.recent(50)).thenReturn(List.of());
        when(outboxService.failedCount()).thenReturn(3);
        when(outboxService.lastError()).thenReturn(Optional.of("Relay unreachable"));

        mvc.perform(get("/postausgang"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/postausgang")))
                .andExpect(content().string(containsString("3 fehlgeschlagen")));

        // When failedCount == 0
        when(outboxService.failedCount()).thenReturn(0);
        when(outboxService.lastError()).thenReturn(Optional.empty());

        mvc.perform(get("/postausgang"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("fehlgeschlagen"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void healthBannerCostsNoErrorQueryWhenNothingHasFailed() throws Exception {
        // The banner advice runs on every request, the 3 s progress poll included (D29 budgets
        // one indexed query per viewer). With no failures there is no error to fetch.
        when(outboxService.recent(50)).thenReturn(List.of());
        when(outboxService.failedCount()).thenReturn(0);

        mvc.perform(get("/postausgang")).andExpect(status().isOk());

        verify(outboxService, never()).lastError();
    }
}
