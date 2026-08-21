package dev.hendrikhoemberg.webtesthelper.runner.persistence;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "run")
public class RunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long siteId;

    @Enumerated(EnumType.STRING)
    private RunTrigger triggerType;

    @Enumerated(EnumType.STRING)
    private RunScope scope;

    @Enumerated(EnumType.STRING)
    private RunStatus status = RunStatus.QUEUED;

    private Instant queuedAt = Instant.now();

    private Instant startedAt;

    private Instant finishedAt;

    private String leaseOwner;

    private Instant leaseExpiresAt;

    private int pagesVisited;

    private int pagesFailed;

    private int findingsTotal;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> coveredCheckTypes = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> coveredUrls = List.of();

    private boolean partialCoverage;

    private String budgetStopReason;

    private Long soft404Simhash;

    private Integer soft404Status;

    private Integer soft404TextLength;

    private Instant baselineAcceptedAt;

    private String errorMessage;

    @Version
    private long version;

    public boolean isBaselineAccepted() {
        return baselineAcceptedAt != null;
    }
}
