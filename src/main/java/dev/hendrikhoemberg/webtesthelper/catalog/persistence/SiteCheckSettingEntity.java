package dev.hendrikhoemberg.webtesthelper.catalog.persistence;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
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

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "site_check_setting")
public class SiteCheckSettingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long siteId;

    @Enumerated(EnumType.STRING)
    private CheckType checkType;

    private boolean enabled;

    @Enumerated(EnumType.STRING)
    private Severity severityOverride;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> config = new HashMap<>();

    @Version
    private long version;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SiteCheckSettingEntity that = (SiteCheckSettingEntity) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
