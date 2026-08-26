package dev.hendrikhoemberg.webtesthelper.catalog.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NotificationRecipientRepository extends JpaRepository<NotificationRecipientEntity, Long> {

    List<NotificationRecipientEntity> findBySiteIdOrderByEmailAsc(Long siteId);

    List<NotificationRecipientEntity> findBySiteIdInOrderByEmailAsc(Collection<Long> siteIds);

    boolean existsBySiteIdAndEmail(Long siteId, String email);

    Optional<NotificationRecipientEntity> findBySiteIdAndEmail(Long siteId, String email);
}
