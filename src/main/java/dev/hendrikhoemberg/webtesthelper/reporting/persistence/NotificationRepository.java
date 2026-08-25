package dev.hendrikhoemberg.webtesthelper.reporting.persistence;

import dev.hendrikhoemberg.webtesthelper.reporting.NotificationState;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    int countByState(NotificationState state);

    @Query("SELECT n.lastError FROM NotificationEntity n WHERE n.state = :state AND n.lastError IS NOT NULL ORDER BY n.createdAt DESC LIMIT 1")
    Optional<String> findFirstLastErrorByState(NotificationState state);

    List<NotificationEntity> findByOrderByCreatedAtDesc(Pageable pageable);
}
