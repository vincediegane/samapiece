package sn.samapiece.iam;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRepository extends JpaRepository<Agent, UUID> {

    Optional<Agent> findByMatricule(String matricule);

    List<Agent> findByPosteId(UUID posteId);

    List<Agent> findByPosteRegionId(UUID regionId);
}
