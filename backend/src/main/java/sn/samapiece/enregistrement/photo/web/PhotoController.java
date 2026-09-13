package sn.samapiece.enregistrement.photo.web;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import sn.samapiece.enregistrement.photo.PhotoService;
import sn.samapiece.enregistrement.photo.PhotoService.PhotoTelechargee;
import sn.samapiece.enregistrement.photo.TypePhoto;

@RestController
@RequestMapping("/api/v1/pieces/{pieceId}/photos")
public class PhotoController {

    private final PhotoService photoService;

    public PhotoController(PhotoService photoService) {
        this.photoService = photoService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")
    public ResponseEntity<UploadPhotoResponse> uploader(
            @PathVariable UUID pieceId,
            @RequestParam TypePhoto type,
            @RequestPart MultipartFile fichier) {
        return ResponseEntity.status(HttpStatus.CREATED).body(photoService.uploader(pieceId, type, fichier));
    }

    @GetMapping("/{photoId}")
    @PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')")
    public ResponseEntity<byte[]> telecharger(@PathVariable UUID pieceId, @PathVariable UUID photoId) {
        PhotoTelechargee photo = photoService.telecharger(pieceId, photoId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.typeMime()))
                .body(photo.octets());
    }
}
