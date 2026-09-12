package sn.samapiece.referentiel;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PosteRepository extends JpaRepository<Poste, UUID> {

    @Override
    @EntityGraph(attributePaths = "region")
    List<Poste> findAll();
}
