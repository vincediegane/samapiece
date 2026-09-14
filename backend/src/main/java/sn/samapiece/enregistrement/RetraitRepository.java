package sn.samapiece.enregistrement;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RetraitRepository extends JpaRepository<Retrait, UUID> {
    List<Retrait> findByPieceIdOrderByCreeLeDesc(UUID pieceId);
}
