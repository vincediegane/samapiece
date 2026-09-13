package sn.samapiece.enregistrement;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PieceRepository extends JpaRepository<Piece, UUID> {
}
