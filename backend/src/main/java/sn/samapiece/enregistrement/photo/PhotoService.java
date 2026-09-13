package sn.samapiece.enregistrement.photo;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import sn.samapiece.enregistrement.Piece;
import sn.samapiece.enregistrement.PieceIntrouvableException;
import sn.samapiece.enregistrement.PieceRepository;
import sn.samapiece.enregistrement.photo.PhotoChiffrementService.PhotoChiffree;
import sn.samapiece.enregistrement.photo.web.UploadPhotoResponse;
import sn.samapiece.iam.AccesRefuseException;
import sn.samapiece.iam.Agent;
import sn.samapiece.iam.AgentRepository;
import sn.samapiece.iam.security.PerimetrePoste;

@Service
public class PhotoService {

    private static final long TAILLE_MAX_OCTETS = 10L * 1024 * 1024;
    private static final Set<String> TYPES_MIME_AUTORISES = Set.of("image/jpeg", "image/png");

    private static final byte[] SIGNATURE_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] SIGNATURE_PNG = {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private final PieceRepository pieceRepository;
    private final AgentRepository agentRepository;
    private final PhotoRepository photoRepository;
    private final PhotoChiffrementService chiffrementService;
    private final PhotoStockageService stockageService;

    public PhotoService(
            PieceRepository pieceRepository,
            AgentRepository agentRepository,
            PhotoRepository photoRepository,
            PhotoChiffrementService chiffrementService,
            PhotoStockageService stockageService) {
        this.pieceRepository = pieceRepository;
        this.agentRepository = agentRepository;
        this.photoRepository = photoRepository;
        this.chiffrementService = chiffrementService;
        this.stockageService = stockageService;
    }

    @Transactional
    public UploadPhotoResponse uploader(UUID pieceId, TypePhoto type, MultipartFile fichier) {
        Agent appelant = appelantCourant();

        Piece piece = pieceRepository.findById(pieceId)
                .orElseThrow(() -> new PieceIntrouvableException(pieceId));

        if (!appelant.getPoste().getId().equals(piece.getPoste().getId())) {
            throw new AccesRefuseException("Poste hors perimetre pour cette piece.");
        }

        if (photoRepository.findByPieceIdAndType(pieceId, type).isPresent()) {
            throw new PhotoDejaExistanteException(
                    "Une photo de type " + type + " existe deja pour cette piece.");
        }

        byte[] octets;
        try {
            octets = fichier.getBytes();
        } catch (IOException e) {
            throw new TypeFichierNonAutoriseException("Impossible de lire le fichier envoye.");
        }

        if (octets.length == 0) {
            throw new TypeFichierNonAutoriseException("Le fichier envoye est vide.");
        }
        if (octets.length > TAILLE_MAX_OCTETS) {
            throw new FichierTropVolumineuxException(
                    "Le fichier depasse la taille maximale autorisee de 10 Mo.");
        }
        String contentTypeDeclare = fichier.getContentType();
        if (contentTypeDeclare == null || !TYPES_MIME_AUTORISES.contains(contentTypeDeclare)) {
            throw new TypeFichierNonAutoriseException(
                    "Type de fichier non autorise : " + contentTypeDeclare);
        }
        String typeMimeDetecte = detecterTypeMime(octets)
                .filter(contentTypeDeclare::equals)
                .orElseThrow(() -> new TypeFichierNonAutoriseException(
                        "Le contenu du fichier ne correspond pas au type declare."));

        PhotoChiffree chiffre = chiffrementService.chiffrer(octets);
        String cleObjet = "pieces/" + pieceId + "/" + UUID.randomUUID() + ".enc";
        stockageService.televerser(cleObjet, chiffre.octetsChiffres());

        Photo photo = new Photo(
                piece,
                type,
                cleObjet,
                typeMimeDetecte,
                octets.length,
                chiffre.ivBase64());
        photoRepository.saveAndFlush(photo);

        return UploadPhotoResponse.of(photo);
    }

    @Transactional(readOnly = true)
    public PhotoTelechargee telecharger(UUID pieceId, UUID photoId) {
        Agent appelant = appelantCourant();

        Piece piece = pieceRepository.findById(pieceId)
                .orElseThrow(() -> new PieceIntrouvableException(pieceId));

        if (!PerimetrePoste.estDansPerimetre(appelant, piece.getPoste())) {
            throw new AccesRefuseException("Poste/region hors perimetre pour cette piece.");
        }

        Photo photo = photoRepository.findByIdAndPieceId(photoId, pieceId)
                .orElseThrow(() -> new PhotoIntrouvableException(photoId));

        byte[] octetsChiffres = stockageService.telecharger(photo.getCleObjetStockage());
        byte[] octetsClair = chiffrementService.dechiffrer(octetsChiffres, photo.getIvChiffrement());

        return new PhotoTelechargee(octetsClair, photo.getTypeMime());
    }

    private Optional<String> detecterTypeMime(byte[] octets) {
        if (correspond(octets, SIGNATURE_JPEG)) {
            return Optional.of("image/jpeg");
        }
        if (correspond(octets, SIGNATURE_PNG)) {
            return Optional.of("image/png");
        }
        return Optional.empty();
    }

    private boolean correspond(byte[] octets, byte[] signature) {
        if (octets.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (octets[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private Agent appelantCourant() {
        String matricule = SecurityContextHolder.getContext().getAuthentication().getName();
        Agent appelant = agentRepository.findByMatricule(matricule)
                .orElseThrow(() -> new AccesRefuseException("Agent appelant introuvable."));
        if (!appelant.isActif()) {
            throw new AccesRefuseException("Agent appelant inactif.");
        }
        return appelant;
    }

    public record PhotoTelechargee(byte[] octets, String typeMime) {}
}
