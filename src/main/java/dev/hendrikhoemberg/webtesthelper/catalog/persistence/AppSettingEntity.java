package dev.hendrikhoemberg.webtesthelper.catalog.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "app_setting")
public class AppSettingEntity {

    @Id
    @Column(name = "setting_key")
    private String settingKey;

    @Column(name = "setting_value")
    private String settingValue;

    @Column(name = "encrypted", nullable = false)
    private boolean encrypted;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public AppSettingEntity(String settingKey, String settingValue, boolean encrypted) {
        this.settingKey = settingKey;
        this.settingValue = settingValue;
        this.encrypted = encrypted;
        this.updatedAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AppSettingEntity that = (AppSettingEntity) o;
        return settingKey != null && settingKey.equals(that.settingKey);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
