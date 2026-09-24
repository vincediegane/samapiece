package sn.samapiece.enregistrement.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import sn.samapiece.enregistrement.Piece;
import sn.samapiece.enregistrement.PieceRepository;
import sn.samapiece.enregistrement.TypeDocument;
import sn.samapiece.iam.Agent;
import sn.samapiece.iam.AgentRepository;
import sn.samapiece.iam.Role;
import sn.samapiece.iam.web.LoginRequest;
import sn.samapiece.referentiel.Poste;
import sn.samapiece.referentiel.PosteRepository;
import sn.samapiece.referentiel.Region;
import sn.samapiece.referentiel.RegionRepository;
import sn.samapiece.referentiel.TypePoste;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PhotoIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static MinIOContainer minio = new MinIOContainer(
            // quay.io/minio/minio et docker.io/minio/minio ne sont plus accessibles anonymement
            // depuis le changement de politique de distribution de MinIO (2025) : 401 Unauthorized
            // sur toute image, y compris `latest`. bitnamilegacy/minio (dépôt Bitnami "legacy",
            // figé, plus d'images publiées depuis leur propre changement de politique) reste
            // public et expose la même API S3/health-check ; vérifié manuellement (mc mb/cp/cat).
            DockerImageName.parse(
                            "bitnamilegacy/minio@sha256:451fe6858cb770cc9d0e77ba811ce287420f781c7c1b806a386f6896471a349c")
                    .asCompatibleSubstituteFor("minio/minio"));

    @DynamicPropertySource
    static void proprietesMinio(DynamicPropertyRegistry registry) {
        registry.add("samapiece.minio.endpoint", minio::getS3URL);
        registry.add("samapiece.minio.access-key", minio::getUserName);
        registry.add("samapiece.minio.secret-key", minio::getPassword);
    }

    private static final String HORAIRES = "{\"lundi\":{\"ouvert\":true,\"debut\":\"08:00\",\"fin\":\"18:00\"}}";
    private static final String MOT_DE_PASSE_CLAIR = "MotDePasse123!";
    private static final String BUCKET_PHOTOS_TEST = "samapiece-photos-test";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PhotoRepository photoRepository;

    @Autowired
    private PieceRepository pieceRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private PosteRepository posteRepository;

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void nettoyer() {
        photoRepository.deleteAll();
        pieceRepository.deleteAll();
        agentRepository.deleteAll();
        posteRepository.deleteAll();
        regionRepository.deleteAll();
    }

    private Region creerRegion(String nom) {
        return regionRepository.save(new Region(nom));
    }

    private Poste creerPoste(Region region, String nom) {
        return posteRepository.save(new Poste(
                region,
                nom,
                TypePoste.POLICE,
                "Adresse " + nom,
                "+221338210000",
                HORAIRES,
                14.6928,
                -17.4467));
    }

    private Poste creerPoste() {
        return creerPoste(creerRegion("Dakar"), "Commissariat Central Dakar");
    }

    private Agent creerAgentActif(Poste poste, String matricule, Role role) {
        return agentRepository.save(
                new Agent(poste, matricule, "Diop Awa", role, passwordEncoder.encode(MOT_DE_PASSE_CLAIR)));
    }

    private String login(String matricule, String motDePasse) throws Exception {
        String corps = OBJECT_MAPPER.writeValueAsString(new LoginRequest(matricule, motDePasse));
        String reponse = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return OBJECT_MAPPER.readTree(reponse).get("accessToken").asText();
    }

    private String creerEtLoginToken(String matricule, Role role, Poste poste) throws Exception {
        creerAgentActif(poste, matricule, role);
        return login(matricule, MOT_DE_PASSE_CLAIR);
    }

    private Piece creerPiece(Poste poste, Agent agentCreateur) {
        return pieceRepository.save(new Piece(
                "PC-TEST-" + UUID.randomUUID().toString().substring(0, 8),
                poste,
                agentCreateur,
                TypeDocument.CNI,
                "Fall",
                "Moussa",
                "hash-de-test-0000000000000000000000000000000000000000000000",
                "sel-de-test",
                "masque",
                null,
                LocalDate.now(),
                null,
                null));
    }

    private byte[] octetsAvecSignatureEtPadding(byte[] signature, int tailleTotale) {
        byte[] octets = new byte[tailleTotale];
        System.arraycopy(signature, 0, octets, 0, signature.length);
        return octets;
    }

    private byte[] octetsJpegValides() {
        return octetsAvecSignatureEtPadding(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, 100);
    }

    private byte[] octetsPngValides() {
        return octetsAvecSignatureEtPadding(
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, 100);
    }

    private byte[] octetsGifNonAutorise() {
        return new byte[] {0x47, 0x49, 0x46, 0x38, 0x39, 0x61};
    }

    private byte[] octetsMensongers() {
        return "ceci n'est pas une image".getBytes(StandardCharsets.UTF_8);
    }

    private byte[] octetsTropVolumineux() {
        return octetsAvecSignatureEtPadding(
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, 10 * 1024 * 1024 + 1);
    }

    @Test
    void uploader_commeAgent_avecFichierValide_shouldRetourner201EtPersisterPhotoChiffree() throws Exception {
        Poste poste = creerPoste();
        Agent agent = creerAgentActif(poste, "PN-2024-00600", Role.AGENT);
        String token = login("PN-2024-00600", MOT_DE_PASSE_CLAIR);
        Piece piece = creerPiece(poste, agent);
        byte[] octetsOriginaux = octetsJpegValides();

        String reponse = mockMvc.perform(multipart("/api/v1/pieces/" + piece.getId() + "/photos")
                        .file(new MockMultipartFile("fichier", "photo.jpg", "image/jpeg", octetsOriginaux))
                        .param("type", "RECTO")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID photoId = UUID.fromString(OBJECT_MAPPER.readTree(reponse).get("id").asText());
        Photo photo = photoRepository.findById(photoId).orElseThrow();
        assertThat(photo.getCleObjetStockage()).isNotBlank();
        assertThat(photo.getIvChiffrement()).isNotBlank();
        assertThat(photo.getTypeMime()).isEqualTo("image/jpeg");

        MinioClient minioClientDeTest = MinioClient.builder()
                .endpoint(minio.getS3URL())
                .credentials(minio.getUserName(), minio.getPassword())
                .build();
        byte[] octetsStockes;
        try (var objet = minioClientDeTest.getObject(GetObjectArgs.builder()
                .bucket(BUCKET_PHOTOS_TEST)
                .object(photo.getCleObjetStockage())
                .build())) {
            octetsStockes = objet.readAllBytes();
        }
        assertThat(octetsStockes).isNotEqualTo(octetsOriginaux);
    }

    @Test
    void telecharger_commeAgentMemePoste_shouldRetourner200EtOctetsIdentiquesAUploadInitial() throws Exception {
        Poste poste = creerPoste();
        Agent agent = creerAgentActif(poste, "PN-2024-00601", Role.AGENT);
        String token = login("PN-2024-00601", MOT_DE_PASSE_CLAIR);
        Piece piece = creerPiece(poste, agent);
        byte[] octetsOriginaux = octetsJpegValides();

        String reponseUpload = mockMvc.perform(multipart("/api/v1/pieces/" + piece.getId() + "/photos")
                        .file(new MockMultipartFile("fichier", "photo.jpg", "image/jpeg", octetsOriginaux))
                        .param("type", "RECTO")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID photoId = UUID.fromString(OBJECT_MAPPER.readTree(reponseUpload).get("id").asText());

        byte[] octetsTelecharges = mockMvc.perform(get(
                        "/api/v1/pieces/" + piece.getId() + "/photos/" + photoId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(octetsTelecharges).isEqualTo(octetsOriginaux);
    }

    @Test
    void uploader_avecTypeGif_shouldRetourner415() throws Exception {
        Poste poste = creerPoste();
        Agent agent = creerAgentActif(poste, "PN-2024-00602", Role.AGENT);
        String token = login("PN-2024-00602", MOT_DE_PASSE_CLAIR);
        Piece piece = creerPiece(poste, agent);

        mockMvc.perform(multipart("/api/v1/pieces/" + piece.getId() + "/photos")
                        .file(new MockMultipartFile("fichier", "photo.gif", "image/gif", octetsGifNonAutorise()))
                        .param("type", "RECTO")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void uploader_avecContentTypeMensonger_shouldRetourner415() throws Exception {
        Poste poste = creerPoste();
        Agent agent = creerAgentActif(poste, "PN-2024-00603", Role.AGENT);
        String token = login("PN-2024-00603", MOT_DE_PASSE_CLAIR);
        Piece piece = creerPiece(poste, agent);

        mockMvc.perform(multipart("/api/v1/pieces/" + piece.getId() + "/photos")
                        .file(new MockMultipartFile("fichier", "photo.png", "image/png", octetsMensongers()))
                        .param("type", "RECTO")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void uploader_avecFichierTropVolumineux_shouldRetourner413() throws Exception {
        Poste poste = creerPoste();
        Agent agent = creerAgentActif(poste, "PN-2024-00604", Role.AGENT);
        String token = login("PN-2024-00604", MOT_DE_PASSE_CLAIR);
        Piece piece = creerPiece(poste, agent);

        mockMvc.perform(multipart("/api/v1/pieces/" + piece.getId() + "/photos")
                        .file(new MockMultipartFile("fichier", "photo.jpg", "image/jpeg", octetsTropVolumineux()))
                        .param("type", "RECTO")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void uploader_sansToken_shouldRetourner401() throws Exception {
        Poste poste = creerPoste();
        Agent agent = creerAgentActif(poste, "PN-2024-00605", Role.AGENT);
        Piece piece = creerPiece(poste, agent);

        mockMvc.perform(multipart("/api/v1/pieces/" + piece.getId() + "/photos")
                        .file(new MockMultipartFile("fichier", "photo.jpg", "image/jpeg", octetsJpegValides()))
                        .param("type", "RECTO"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void telecharger_sansToken_shouldRetourner401() throws Exception {
        Poste poste = creerPoste();
        Agent agent = creerAgentActif(poste, "PN-2024-00606", Role.AGENT);
        Piece piece = creerPiece(poste, agent);

        mockMvc.perform(get("/api/v1/pieces/" + piece.getId() + "/photos/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void uploader_commeAgentDeAutrePoste_shouldRetourner403() throws Exception {
        Region region = creerRegion("Dakar");
        Poste postePiece = creerPoste(region, "Poste Piece");
        Poste posteAutre = creerPoste(region, "Autre Poste");
        Agent agentCreateur = creerAgentActif(postePiece, "PN-2024-00607", Role.AGENT);
        Piece piece = creerPiece(postePiece, agentCreateur);
        String token = creerEtLoginToken("PN-2024-00608", Role.AGENT, posteAutre);

        mockMvc.perform(multipart("/api/v1/pieces/" + piece.getId() + "/photos")
                        .file(new MockMultipartFile("fichier", "photo.jpg", "image/jpeg", octetsJpegValides()))
                        .param("type", "RECTO")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void telecharger_commeAgentDeAutrePoste_shouldRetourner403() throws Exception {
        Region region = creerRegion("Dakar");
        Poste postePiece = creerPoste(region, "Poste Piece");
        Poste posteAutre = creerPoste(region, "Autre Poste");
        Agent agentCreateur = creerAgentActif(postePiece, "PN-2024-00609", Role.AGENT);
        String tokenCreateur = login("PN-2024-00609", MOT_DE_PASSE_CLAIR);
        Piece piece = creerPiece(postePiece, agentCreateur);
        String reponseUpload = mockMvc.perform(multipart("/api/v1/pieces/" + piece.getId() + "/photos")
                        .file(new MockMultipartFile("fichier", "photo.jpg", "image/jpeg", octetsJpegValides()))
                        .param("type", "RECTO")
                        .header("Authorization", "Bearer " + tokenCreateur))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID photoId = UUID.fromString(OBJECT_MAPPER.readTree(reponseUpload).get("id").asText());
        String tokenAutre = creerEtLoginToken("PN-2024-00610", Role.AGENT, posteAutre);

        mockMvc.perform(get("/api/v1/pieces/" + piece.getId() + "/photos/" + photoId)
                        .header("Authorization", "Bearer " + tokenAutre))
                .andExpect(status().isForbidden());
    }

    @Test
    void telecharger_commeChefPosteDeAutrePoste_shouldRetourner403() throws Exception {
        Region region = creerRegion("Dakar");
        Poste postePiece = creerPoste(region, "Poste Piece");
        Poste posteAutre = creerPoste(region, "Autre Poste");
        Agent agentCreateur = creerAgentActif(postePiece, "PN-2024-00611", Role.AGENT);
        String tokenCreateur = login("PN-2024-00611", MOT_DE_PASSE_CLAIR);
        Piece piece = creerPiece(postePiece, agentCreateur);
        String reponseUpload = mockMvc.perform(multipart("/api/v1/pieces/" + piece.getId() + "/photos")
                        .file(new MockMultipartFile("fichier", "photo.jpg", "image/jpeg", octetsJpegValides()))
                        .param("type", "RECTO")
                        .header("Authorization", "Bearer " + tokenCreateur))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID photoId = UUID.fromString(OBJECT_MAPPER.readTree(reponseUpload).get("id").asText());
        String tokenChefAutre = creerEtLoginToken("PN-2024-00612", Role.CHEF_POSTE, posteAutre);

        mockMvc.perform(get("/api/v1/pieces/" + piece.getId() + "/photos/" + photoId)
                        .header("Authorization", "Bearer " + tokenChefAutre))
                .andExpect(status().isForbidden());
    }

    @Test
    void telecharger_commeAdminRegionalMemeRegionAutrePoste_shouldRetourner200() throws Exception {
        Region region = creerRegion("Dakar");
        Poste postePiece = creerPoste(region, "Poste Piece");
        Poste posteAdmin = creerPoste(region, "Poste Admin");
        Agent agentCreateur = creerAgentActif(postePiece, "PN-2024-00613", Role.AGENT);
        String tokenCreateur = login("PN-2024-00613", MOT_DE_PASSE_CLAIR);
        Piece piece = creerPiece(postePiece, agentCreateur);
        String reponseUpload = mockMvc.perform(multipart("/api/v1/pieces/" + piece.getId() + "/photos")
                        .file(new MockMultipartFile("fichier", "photo.jpg", "image/jpeg", octetsJpegValides()))
                        .param("type", "RECTO")
                        .header("Authorization", "Bearer " + tokenCreateur))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID photoId = UUID.fromString(OBJECT_MAPPER.readTree(reponseUpload).get("id").asText());
        String tokenAdmin = creerEtLoginToken("PN-2024-00614", Role.ADMIN_REGIONAL, posteAdmin);

        mockMvc.perform(get("/api/v1/pieces/" + piece.getId() + "/photos/" + photoId)
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isOk());
    }

    @Test
    void telecharger_commeAdminRegionalHorsRegion_shouldRetourner403() throws Exception {
        Poste postePiece = creerPoste(creerRegion("Dakar"), "Poste Piece");
        Poste posteAdmin = creerPoste(creerRegion("Thies"), "Poste Admin");
        Agent agentCreateur = creerAgentActif(postePiece, "PN-2024-00615", Role.AGENT);
        String tokenCreateur = login("PN-2024-00615", MOT_DE_PASSE_CLAIR);
        Piece piece = creerPiece(postePiece, agentCreateur);
        String reponseUpload = mockMvc.perform(multipart("/api/v1/pieces/" + piece.getId() + "/photos")
                        .file(new MockMultipartFile("fichier", "photo.jpg", "image/jpeg", octetsJpegValides()))
                        .param("type", "RECTO")
                        .header("Authorization", "Bearer " + tokenCreateur))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID photoId = UUID.fromString(OBJECT_MAPPER.readTree(reponseUpload).get("id").asText());
        String tokenAdmin = creerEtLoginToken("PN-2024-00616", Role.ADMIN_REGIONAL, posteAdmin);

        mockMvc.perform(get("/api/v1/pieces/" + piece.getId() + "/photos/" + photoId)
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isForbidden());
    }

    @Test
    void telecharger_commeAdminNational_shouldRetourner200() throws Exception {
        Poste postePiece = creerPoste(creerRegion("Dakar"), "Poste Piece");
        Poste posteAdmin = creerPoste(creerRegion("Thies"), "Poste Admin");
        Agent agentCreateur = creerAgentActif(postePiece, "PN-2024-00617", Role.AGENT);
        String tokenCreateur = login("PN-2024-00617", MOT_DE_PASSE_CLAIR);
        Piece piece = creerPiece(postePiece, agentCreateur);
        String reponseUpload = mockMvc.perform(multipart("/api/v1/pieces/" + piece.getId() + "/photos")
                        .file(new MockMultipartFile("fichier", "photo.jpg", "image/jpeg", octetsJpegValides()))
                        .param("type", "RECTO")
                        .header("Authorization", "Bearer " + tokenCreateur))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID photoId = UUID.fromString(OBJECT_MAPPER.readTree(reponseUpload).get("id").asText());
        String tokenAdmin = creerEtLoginToken("PN-2024-00618", Role.ADMIN_NATIONAL, posteAdmin);

        mockMvc.perform(get("/api/v1/pieces/" + piece.getId() + "/photos/" + photoId)
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isOk());
    }

    @Test
    void telecharger_commeAuditeur_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00619", Role.AGENT);
        String tokenCreateur = login("PN-2024-00619", MOT_DE_PASSE_CLAIR);
        Piece piece = creerPiece(poste, agentCreateur);
        String reponseUpload = mockMvc.perform(multipart("/api/v1/pieces/" + piece.getId() + "/photos")
                        .file(new MockMultipartFile("fichier", "photo.jpg", "image/jpeg", octetsJpegValides()))
                        .param("type", "RECTO")
                        .header("Authorization", "Bearer " + tokenCreateur))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID photoId = UUID.fromString(OBJECT_MAPPER.readTree(reponseUpload).get("id").asText());
        String tokenAuditeur = creerEtLoginToken("PN-2024-00620", Role.AUDITEUR, poste);

        mockMvc.perform(get("/api/v1/pieces/" + piece.getId() + "/photos/" + photoId)
                        .header("Authorization", "Bearer " + tokenAuditeur))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploader_commeAdminRegional_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00621", Role.AGENT);
        Piece piece = creerPiece(poste, agentCreateur);
        String token = creerEtLoginToken("PN-2024-00622", Role.ADMIN_REGIONAL, poste);

        mockMvc.perform(multipart("/api/v1/pieces/" + piece.getId() + "/photos")
                        .file(new MockMultipartFile("fichier", "photo.jpg", "image/jpeg", octetsJpegValides()))
                        .param("type", "RECTO")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploader_commeAdminNational_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00623", Role.AGENT);
        Piece piece = creerPiece(poste, agentCreateur);
        String token = creerEtLoginToken("PN-2024-00624", Role.ADMIN_NATIONAL, poste);

        mockMvc.perform(multipart("/api/v1/pieces/" + piece.getId() + "/photos")
                        .file(new MockMultipartFile("fichier", "photo.jpg", "image/jpeg", octetsJpegValides()))
                        .param("type", "RECTO")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploader_commeAuditeur_shouldRetourner403() throws Exception {
        Poste poste = creerPoste();
        Agent agentCreateur = creerAgentActif(poste, "PN-2024-00625", Role.AGENT);
        Piece piece = creerPiece(poste, agentCreateur);
        String token = creerEtLoginToken("PN-2024-00626", Role.AUDITEUR, poste);

        mockMvc.perform(multipart("/api/v1/pieces/" + piece.getId() + "/photos")
                        .file(new MockMultipartFile("fichier", "photo.jpg", "image/jpeg", octetsJpegValides()))
                        .param("type", "RECTO")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploader_avecTypeDejaPresentPourLaPiece_shouldRetourner409() throws Exception {
        Poste poste = creerPoste();
        Agent agent = creerAgentActif(poste, "PN-2024-00627", Role.AGENT);
        String token = login("PN-2024-00627", MOT_DE_PASSE_CLAIR);
        Piece piece = creerPiece(poste, agent);

        mockMvc.perform(multipart("/api/v1/pieces/" + piece.getId() + "/photos")
                        .file(new MockMultipartFile("fichier", "photo.jpg", "image/jpeg", octetsJpegValides()))
                        .param("type", "RECTO")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart("/api/v1/pieces/" + piece.getId() + "/photos")
                        .file(new MockMultipartFile("fichier", "photo2.jpg", "image/jpeg", octetsJpegValides()))
                        .param("type", "RECTO")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    void uploader_avecTypesDifferentsPourLaMemePiece_shouldPermettreLesDeux() throws Exception {
        Poste poste = creerPoste();
        Agent agent = creerAgentActif(poste, "PN-2024-00628", Role.AGENT);
        String token = login("PN-2024-00628", MOT_DE_PASSE_CLAIR);
        Piece piece = creerPiece(poste, agent);

        mockMvc.perform(multipart("/api/v1/pieces/" + piece.getId() + "/photos")
                        .file(new MockMultipartFile("fichier", "recto.jpg", "image/jpeg", octetsJpegValides()))
                        .param("type", "RECTO")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart("/api/v1/pieces/" + piece.getId() + "/photos")
                        .file(new MockMultipartFile("fichier", "verso.png", "image/png", octetsPngValides()))
                        .param("type", "VERSO")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());

        assertThat(photoRepository.count()).isEqualTo(2);
    }
}
