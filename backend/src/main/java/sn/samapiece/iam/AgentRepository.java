package sn.samapiece.iam;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRepository extends JpaRepository<Agent, UUID> {

    Optional<Agent> findByMatricule(String matricule);
}
