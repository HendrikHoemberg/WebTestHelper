package dev.hendrikhoemberg.webtesthelper.catalog.persistence;

import dev.hendrikhoemberg.webtesthelper.model.FormTestMode;
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
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "site")
public class SiteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String baseUrl;

    private boolean enabled = true;

    private int maxPages = 300;

    private int maxDepth = 5;

    private int maxDurationSeconds = 1800;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> includePatterns = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> excludePatterns = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> pinnedKeyPages = new ArrayList<>();

    private boolean respectRobots = true;

    private String userAgent;

    @Enumerated(EnumType.STRING)
    private FormTestMode formTestMode = FormTestMode.NO_SUBMIT;

    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();

    @Version
    private long version;
}
