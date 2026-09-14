package sn.samapiece.alertes;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlerteRepository extends JpaRepository<Alerte, UUID> {
}
