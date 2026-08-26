package dev.hendrikhoemberg.webtesthelper.reporting;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DigestScheduledJobTest {

    @Test
    void cycleDelegatesToDigestServiceWithCurrentTime() {
        DigestService digestService = mock(DigestService.class);
        DigestScheduledJob job = new DigestScheduledJob(digestService);

        Instant before = Instant.now();
        job.cycle();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(digestService).runCycle(captor.capture());

        Instant passedTime = captor.getValue();
        assertThat(passedTime).isNotNull();
        assertThat(passedTime).isAfterOrEqualTo(before);
        assertThat(passedTime).isBeforeOrEqualTo(after);
    }
}
