package dev.hendrikhoemberg.webtesthelper.scheduling.persistence;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "schedule")
public class ScheduleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long siteId;

    @Enumerated(EnumType.STRING)
    private RunScope scope;

    private String cron;

    private String timezone = "Europe/Berlin";

    private boolean enabled = true;

    private Instant lastFiredAt;

    private Instant nextFireAt;

    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();

    @Version
    private long version;
}
