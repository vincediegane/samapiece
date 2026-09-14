package sn.samapiece.enregistrement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import sn.samapiece.enregistrement.NumeroDocumentHasher.NumeroDocumentHache;
import sn.samapiece.iam.Agent;
import sn.samapiece.iam.Role;
import sn.samapiece.referentiel.Poste;
import sn.samapiece.referentiel.TypePoste;

class PieceTest {

    private static final String NUMERO_CLAIR_TEST = "S1234567";

    private Poste poste() {
        return new Poste(
                null, "Commissariat Central Dakar", TypePoste.POLICE,
                "Place de l'Indépendance, Dakar", null, "{}", null, null);
    }

    private Agent agentCreateur() {
        return new Agent(poste(), "PN-2024-00123", "Diop Awa", Role.AGENT, "$2a$10$hashopaque");
    }

    private Piece nouvellePiece() {
        NumeroDocumentHache hache = new NumeroDocumentHasher().hacher(NUMERO_CLAIR_TEST);
        return new Piece(
                "PC-3F2A9C1B-2026-00001",
                poste(),
                agentCreateur(),
                TypeDocument.CNI,
                "Fall",
                "Moussa",
                hache.hash(),
                hache.sel(),
                hache.masque(),
                LocalDate.of(1990, 1, 1),
                LocalDate.now(),
                null,
                null);
    }

    @Test
    void nouvellePiece_shouldAvoirStatutDisponibleParDefaut() {
        Piece piece = nouvellePiece();

        assertThat(piece.getStatut()).isEqualTo(StatutPiece.DISPONIBLE);
    }

    @Test
    void piece_shouldNeJamaisExposerLeNumeroEnClair() throws IllegalAccessException {
        Piece piece = nouvellePiece();

        for (Field field : Piece.class.getDeclaredFields()) {
            assertThat(field.getName()).isNotEqualTo("numeroDocument");

            if (field.getType().equals(String.class)) {
                field.setAccessible(true);
                Object valeur = field.get(piece);
                assertThat(valeur).isNotEqualTo(NUMERO_CLAIR_TEST);
            }
        }
    }

    @Test
    void piece_shouldAvoirUnSeulConstructeurPublicNAcceptantAucunNumeroBrutCandidat() {
        Constructor<?>[] constructeurs = Piece.class.getConstructors();

        assertThat(constructeurs).hasSize(1);
        assertThat(constructeurs[0].getParameterTypes()).containsExactly(
                String.class,
                Poste.class,
                Agent.class,
                TypeDocument.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                LocalDate.class,
                LocalDate.class,
                String.class,
                String.class);
    }

    @ParameterizedTest
    @EnumSource(value = StatutPiece.class, names = {"DISPONIBLE", "RECLAMEE"})
    void retirer_avecStatutAutoriseTransition_shouldPasserRetiree(StatutPiece statutInitial) {
        Piece piece = nouvellePiece();
        ReflectionTestUtils.setField(piece, "statut", statutInitial);

        piece.retirer();

        assertThat(piece.getStatut()).isEqualTo(StatutPiece.RETIREE);
    }

    @ParameterizedTest
    @EnumSource(
            value = StatutPiece.class,
            names = {"RETIREE", "LITIGE", "ARCHIVEE", "DETRUITE", "SIGNALEE"})
    void retirer_avecStatutNonAutorise_shouldLeverTransitionStatutInterdite(StatutPiece statutInitial) {
        Piece piece = nouvellePiece();
        ReflectionTestUtils.setField(piece, "statut", statutInitial);

        assertThatThrownBy(piece::retirer).isInstanceOf(TransitionStatutInterditeException.class);
        assertThat(piece.getStatut()).isEqualTo(statutInitial);
    }

    @ParameterizedTest
    @EnumSource(value = StatutPiece.class, names = {"LITIGE", "SIGNALEE"})
    void signaler_avecStatutCibleValideEtPieceDisponible_shouldMettreAJourLesChampsDeSignalement(
            StatutPiece statutCible) {
        Piece piece = nouvellePiece();
        Agent signalePar = agentCreateur();

        piece.signaler(statutCible, "Suspicion de fraude", signalePar);

        assertThat(piece.getStatut()).isEqualTo(statutCible);
        assertThat(piece.getSignalePar()).isEqualTo(signalePar);
        assertThat(piece.getSignaleLe()).isNotNull();
        assertThat(piece.getMotifSignalement()).isEqualTo("Suspicion de fraude");
    }

    @Test
    void signaler_avecStatutCibleInvalide_shouldLeverIllegalArgumentException() {
        Piece piece = nouvellePiece();

        assertThatThrownBy(() -> piece.signaler(StatutPiece.DISPONIBLE, "motif", agentCreateur()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(
            value = StatutPiece.class,
            names = {"RETIREE", "LITIGE", "ARCHIVEE", "DETRUITE", "SIGNALEE"})
    void signaler_avecStatutCourantNonAutorise_shouldLeverTransitionStatutInterdite(StatutPiece statutInitial) {
        Piece piece = nouvellePiece();
        ReflectionTestUtils.setField(piece, "statut", statutInitial);

        assertThatThrownBy(() -> piece.signaler(StatutPiece.LITIGE, "motif", agentCreateur()))
                .isInstanceOf(TransitionStatutInterditeException.class);
    }

    @ParameterizedTest
    @EnumSource(value = StatutPiece.class, names = {"RETIREE", "ARCHIVEE", "LITIGE", "SIGNALEE"})
    void debloquer_avecStatutAutorise_shouldPasserDisponibleEtMettreAJourLesChampsDeDeblocage(
            StatutPiece statutInitial) {
        Piece piece = nouvellePiece();
        ReflectionTestUtils.setField(piece, "statut", statutInitial);
        Agent debloquePar = agentCreateur();

        piece.debloquer("Erreur d'identification", debloquePar);

        assertThat(piece.getStatut()).isEqualTo(StatutPiece.DISPONIBLE);
        assertThat(piece.getDebloquePar()).isEqualTo(debloquePar);
        assertThat(piece.getDebloqueLe()).isNotNull();
        assertThat(piece.getMotifDeblocage()).isEqualTo("Erreur d'identification");
    }

    @ParameterizedTest
    @EnumSource(value = StatutPiece.class, names = {"DISPONIBLE", "RECLAMEE", "DETRUITE"})
    void debloquer_avecStatutNonEligible_shouldLeverTransitionStatutInterdite(StatutPiece statutInitial) {
        Piece piece = nouvellePiece();
        ReflectionTestUtils.setField(piece, "statut", statutInitial);

        assertThatThrownBy(() -> piece.debloquer("motif", agentCreateur()))
                .isInstanceOf(TransitionStatutInterditeException.class);
    }
}
