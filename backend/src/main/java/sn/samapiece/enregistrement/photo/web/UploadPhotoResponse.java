package sn.samapiece.enregistrement.photo.web;

import java.time.OffsetDateTime;
import java.util.UUID;
import sn.samapiece.enregistrement.photo.Photo;

public record UploadPhotoResponse(
        UUID id,
        UUID pieceId,
        String type,
        String typeMime,
        long tailleOctets,
        OffsetDateTime creeLe) {

    public static UploadPhotoResponse of(Photo photo) {
        return new UploadPhotoResponse(
                photo.getId(),
                photo.getPiece().getId(),
                photo.getType().name(),
                photo.getTypeMime(),
                photo.getTailleOctets(),
                photo.getCreeLe());
    }
}
