package sn.samapiece.enregistrement.photo;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PhotoRepository extends JpaRepository<Photo, UUID> {
    Optional<Photo> findByPieceIdAndType(UUID pieceId, TypePhoto type);

    Optional<Photo> findByIdAndPieceId(UUID id, UUID pieceId);
}
