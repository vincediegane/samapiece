package sn.samapiece.alertes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationCorrespondanceRepository extends JpaRepository<NotificationCorrespondance, UUID> {

    List<NotificationCorrespondance> findTop50ByStatutAndProchaineTentativeLessThanEqualOrderByProchaineTentativeAsc(
            NotificationCorrespondance.Statut statut, OffsetDateTime maintenant);
}
