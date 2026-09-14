package sn.samapiece.alertes;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlerteDesinscriptionTokenRepository extends JpaRepository<AlerteDesinscriptionToken, UUID> {

    Optional<AlerteDesinscriptionToken> findByTokenHash(String tokenHash);
}
