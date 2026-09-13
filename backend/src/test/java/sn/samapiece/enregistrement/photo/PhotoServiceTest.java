package sn.samapiece.enregistrement.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import sn.samapiece.enregistrement.Piece;
import sn.samapiece.enregistrement.PieceIntrouvableException;
import sn.samapiece.enregistrement.PieceRepository;
import sn.samapiece.enregistrement.TypeDocument;
import sn.samapiece.enregistrement.photo.PhotoChiffrementService.PhotoChiffree;
import sn.samapiece.enregistrement.photo.PhotoService.PhotoTelechargee;
import sn.samapiece.enregistrement.photo.web.UploadPhotoResponse;
import sn.samapiece.iam.AccesRefuseException;
import sn.samapiece.iam.Agent;
import sn.samapiece.iam.AgentRepository;
import sn.samapiece.iam.Role;
import sn.samapiece.referentiel.Poste;
import sn.samapiece.referentiel.Region;
import sn.samapiece.referentiel.TypePoste;

class PhotoServiceTest {

    private static final byte[] SIGNATURE_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] SIGNATURE_PNG = {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private final PieceRepository pieceRepository = mock(PieceRepository.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final PhotoRepository photoRepository = mock(PhotoRepository.class);
    private final PhotoChiffrementService chiffrementService = mock(PhotoChiffrementService.class);
    private final PhotoStockageService stockageService = mock(PhotoStockageService.class);

    private final PhotoService photoService = new PhotoService(
            pieceRepository, agentRepository, photoRepository, chiffrementService, stockageService);

    private Region region() {
        return new Region("Dakar");
    }

    private Poste poste() {
        Poste poste = new Poste(
                region(), "Commissariat Central Dakar", TypePoste.POLICE,
                "Place de l'Indépendance, Dakar", null, "{}", null, null);
        ReflectionTestUtils.setField(poste, "id", UUID.randomUUID());
        return poste;
    }

    private Agent agent(Poste poste, Role role) {
        return new Agent(poste, "PN-2024-00123", "Diop Awa", role, "$2a$10$hashopaque");
    }

    private Piece piece(Poste poste) {
        return new Piece(
                "PC-3F2A9C1B-2026-00001",
                poste,
                agent(poste, Role.AGENT),
                TypeDocument.CNI,
                "Fall",
                "Moussa",
                "hash",
                "sel",
                "masque",
                null,
                java.time.LocalDate.now(),
                null,
                null);
    }

    private void connecterCommeAppelant(Agent appelant) {
        when(agentRepository.findByMatricule(appelant.getMatricule())).thenReturn(Optional.of(appelant));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(appelant.getMatricule(), null, java.util.List.of()));
    }

    private byte[] octetsAvecSignatureEtPadding(byte[] signature, int tailleTotale) {
        byte[] octets = new byte[tailleTotale];
        System.arraycopy(signature, 0, octets, 0, signature.length);
        return octets;
    }

    @AfterEach
    void nettoyerContexteSecurite() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void reinitialiserMocks() {
        Mockito.reset(pieceRepository, agentRepository, photoRepository, chiffrementService, stockageService);
    }

    @Test
    void uploader_avecFichierValide_shouldChiffrerAvantAppelStockageEtPersisterReference() {
        Poste poste = poste();
        Agent appelant = agent(poste, Role.AGENT);
        connecterCommeAppelant(appelant);
        Piece piece = piece(poste);
        UUID pieceId = UUID.randomUUID();
        when(pieceRepository.findById(pieceId)).thenReturn(Optional.of(piece));
        when(photoRepository.findByPieceIdAndType(pieceId, TypePhoto.RECTO)).thenReturn(Optional.empty());

        byte[] octetsClair = octetsAvecSignatureEtPadding(SIGNATURE_JPEG, 100);
        MockMultipartFile fichier = new MockMultipartFile("fichier", "photo.jpg", "image/jpeg", octetsClair);

        byte[] octetsChiffres = "chiffre".getBytes(StandardCharsets.UTF_8);
        PhotoChiffree chiffre = new PhotoChiffree(octetsChiffres, "iv-base64");
        when(chiffrementService.chiffrer(octetsClair)).thenReturn(chiffre);

        UploadPhotoResponse reponse = photoService.uploader(pieceId, TypePhoto.RECTO, fichier);

        InOrder ordre = Mockito.inOrder(chiffrementService, stockageService, photoRepository);
        ordre.verify(chiffrementService).chiffrer(octetsClair);
        ordre.verify(stockageService).televerser(anyString(), eq(octetsChiffres));
        ordre.verify(photoRepository).saveAndFlush(any(Photo.class));

        verify(stockageService, never()).televerser(anyString(), eq(octetsClair));

        assertThat(reponse.type()).isEqualTo("RECTO");
        assertThat(reponse.typeMime()).isEqualTo("image/jpeg");
        assertThat(reponse.tailleOctets()).isEqualTo(octetsClair.length);
    }

    @Test
    void uploader_avecContentTypeNonAutorise_shouldLeverTypeFichierNonAutoriseException() {
        Poste poste = poste();
        Agent appelant = agent(poste, Role.AGENT);
        connecterCommeAppelant(appelant);
        Piece piece = piece(poste);
        UUID pieceId = UUID.randomUUID();
        when(pieceRepository.findById(pieceId)).thenReturn(Optional.of(piece));
        when(photoRepository.findByPieceIdAndType(pieceId, TypePhoto.RECTO)).thenReturn(Optional.empty());

        byte[] octets = {0x47, 0x49, 0x46, 0x38, 0x39, 0x61};
        MockMultipartFile fichier = new MockMultipartFile("fichier", "photo.gif", "image/gif", octets);

        assertThatThrownBy(() -> photoService.uploader(pieceId, TypePhoto.RECTO, fichier))
                .isInstanceOf(TypeFichierNonAutoriseException.class);
    }

    @Test
    void uploader_avecMagicBytesNeCorrespondantPasAuContentTypeDeclare_shouldLeverTypeFichierNonAutoriseException() {
        Poste poste = poste();
        Agent appelant = agent(poste, Role.AGENT);
        connecterCommeAppelant(appelant);
        Piece piece = piece(poste);
        UUID pieceId = UUID.randomUUID();
        when(pieceRepository.findById(pieceId)).thenReturn(Optional.of(piece));
        when(photoRepository.findByPieceIdAndType(pieceId, TypePhoto.RECTO)).thenReturn(Optional.empty());

        byte[] octetsMensongers = "ceci n'est pas une image".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile fichier = new MockMultipartFile("fichier", "photo.png", "image/png", octetsMensongers);

        assertThatThrownBy(() -> photoService.uploader(pieceId, TypePhoto.RECTO, fichier))
                .isInstanceOf(TypeFichierNonAutoriseException.class);
    }

    @Test
    void uploader_avecFichierTropVolumineux_shouldLeverFichierTropVolumineuxException() {
        Poste poste = poste();
        Agent appelant = agent(poste, Role.AGENT);
        connecterCommeAppelant(appelant);
        Piece piece = piece(poste);
        UUID pieceId = UUID.randomUUID();
        when(pieceRepository.findById(pieceId)).thenReturn(Optional.of(piece));
        when(photoRepository.findByPieceIdAndType(pieceId, TypePhoto.RECTO)).thenReturn(Optional.empty());

        byte[] octetsTropVolumineux = octetsAvecSignatureEtPadding(SIGNATURE_JPEG, 10 * 1024 * 1024 + 1);
        MockMultipartFile fichier =
                new MockMultipartFile("fichier", "photo.jpg", "image/jpeg", octetsTropVolumineux);

        assertThatThrownBy(() -> photoService.uploader(pieceId, TypePhoto.RECTO, fichier))
                .isInstanceOf(FichierTropVolumineuxException.class);
    }

    @Test
    void uploader_avecPhotoDejaExistantePourCeType_shouldLeverPhotoDejaExistanteException() {
        Poste poste = poste();
        Agent appelant = agent(poste, Role.AGENT);
        connecterCommeAppelant(appelant);
        Piece piece = piece(poste);
        UUID pieceId = UUID.randomUUID();
        when(pieceRepository.findById(pieceId)).thenReturn(Optional.of(piece));
        when(photoRepository.findByPieceIdAndType(pieceId, TypePhoto.RECTO))
                .thenReturn(Optional.of(mock(Photo.class)));

        byte[] octets = octetsAvecSignatureEtPadding(SIGNATURE_PNG, 20);
        MockMultipartFile fichier = new MockMultipartFile("fichier", "photo.png", "image/png", octets);

        assertThatThrownBy(() -> photoService.uploader(pieceId, TypePhoto.RECTO, fichier))
                .isInstanceOf(PhotoDejaExistanteException.class);
    }

    @Test
    void uploader_avecPieceInconnue_shouldLeverPieceIntrouvableException() {
        Agent appelant = agent(poste(), Role.AGENT);
        connecterCommeAppelant(appelant);
        UUID pieceId = UUID.randomUUID();
        when(pieceRepository.findById(pieceId)).thenReturn(Optional.empty());

        MockMultipartFile fichier = new MockMultipartFile(
                "fichier", "photo.jpg", "image/jpeg", octetsAvecSignatureEtPadding(SIGNATURE_JPEG, 20));

        assertThatThrownBy(() -> photoService.uploader(pieceId, TypePhoto.RECTO, fichier))
                .isInstanceOf(PieceIntrouvableException.class);
    }

    @Test
    void uploader_avecAgentDunAutrePoste_shouldLeverAccesRefuseException() {
        Poste postePiece = poste();
        Poste postoAppelant = poste();
        Agent appelant = agent(postoAppelant, Role.AGENT);
        connecterCommeAppelant(appelant);
        Piece piece = piece(postePiece);
        UUID pieceId = UUID.randomUUID();
        when(pieceRepository.findById(pieceId)).thenReturn(Optional.of(piece));

        MockMultipartFile fichier = new MockMultipartFile(
                "fichier", "photo.jpg", "image/jpeg", octetsAvecSignatureEtPadding(SIGNATURE_JPEG, 20));

        assertThatThrownBy(() -> photoService.uploader(pieceId, TypePhoto.RECTO, fichier))
                .isInstanceOf(AccesRefuseException.class);
    }

    @Test
    void telecharger_avecPhotoAppartenantAUneAutrePiece_shouldLeverPhotoIntrouvableException() {
        Poste poste = poste();
        Agent appelant = agent(poste, Role.AGENT);
        connecterCommeAppelant(appelant);
        Piece piece = piece(poste);
        UUID pieceId = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();
        when(pieceRepository.findById(pieceId)).thenReturn(Optional.of(piece));
        when(photoRepository.findByIdAndPieceId(photoId, pieceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> photoService.telecharger(pieceId, photoId))
                .isInstanceOf(PhotoIntrouvableException.class);
    }

    @Test
    void telecharger_avecFichierValide_shouldDechiffrerApresTelechargement() {
        Poste poste = poste();
        Agent appelant = agent(poste, Role.AGENT);
        connecterCommeAppelant(appelant);
        Piece piece = piece(poste);
        UUID pieceId = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();
        Photo photo = new Photo(piece, TypePhoto.RECTO, "pieces/x/y.enc", "image/jpeg", 10, "iv-base64");
        when(pieceRepository.findById(pieceId)).thenReturn(Optional.of(piece));
        when(photoRepository.findByIdAndPieceId(photoId, pieceId)).thenReturn(Optional.of(photo));
        byte[] octetsChiffres = "chiffre".getBytes(StandardCharsets.UTF_8);
        byte[] octetsClair = "clair".getBytes(StandardCharsets.UTF_8);
        when(stockageService.telecharger("pieces/x/y.enc")).thenReturn(octetsChiffres);
        when(chiffrementService.dechiffrer(octetsChiffres, "iv-base64")).thenReturn(octetsClair);

        PhotoTelechargee resultat = photoService.telecharger(pieceId, photoId);

        assertThat(resultat.octets()).isEqualTo(octetsClair);
        assertThat(resultat.typeMime()).isEqualTo("image/jpeg");
    }
}
