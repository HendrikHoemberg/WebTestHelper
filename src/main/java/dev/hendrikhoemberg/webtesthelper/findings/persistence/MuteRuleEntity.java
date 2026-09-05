package dev.hendrikhoemberg.webtesthelper.findings.persistence;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
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
@Table(name = "mute_rule")
public class MuteRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long siteId;

    @Enumerated(EnumType.STRING)
    private CheckType checkType;

    private String subjectPattern;

    private String locationPattern;

    private String reason;

    private String createdBy;

    private Instant expiresAt;

    private Instant expiredAt;

    private Instant createdAt = Instant.now();

    @Version
    private long version;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MuteRuleEntity that = (MuteRuleEntity) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
