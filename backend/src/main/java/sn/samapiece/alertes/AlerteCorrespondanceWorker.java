package sn.samapiece.alertes;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sn.samapiece.enregistrement.Piece;
import sn.samapiece.enregistrement.PieceRepository;
import sn.samapiece.notifications.NumeroTelephone;
import sn.samapiece.notifications.PasserelleSms;

/**
 * Traite periodiquement les {@link NotificationCorrespondance} echues : recharge l'alerte et la
 * piece fraiches, re-verifie que l'alerte est toujours active, envoie le SMS via
 * {@link PasserelleSms}, et gere le backoff (30 s, 2 min, puis 10 min) jusqu'au nombre maximal de
 * tentatives, apres quoi la notification passe en {@code ECHEC}.
 *
 * <p>Concu pour une instance unique du backend : deux instances pourraient traiter la meme ligne.
 */
@Component
public class AlerteCorrespondanceWorker {

    private static final Logger LOG = LoggerFactory.getLogger(AlerteCorrespondanceWorker.class);

    private final NotificationCorrespondanceRepository notificationRepository;
    private final AlerteRepository alerteRepository;
    private final PieceRepository pieceRepository;
    private final AlerteContactChiffrementService chiffrementService;
    private final PasserelleSms passerelleSms;
    private final AlerteCorrespondanceProperties proprietes;
    private final TransactionTemplate transactionTemplate;

    public AlerteCorrespondanceWorker(
            NotificationCorrespondanceRepository notificationRepository,
            AlerteRepository alerteRepository,
            PieceRepository pieceRepository,
            AlerteContactChiffrementService chiffrementService,
            PasserelleSms passerelleSms,
            AlerteCorrespondanceProperties proprietes,
            PlatformTransactionManager transactionManager) {
        this.notificationRepository = notificationRepository;
        this.alerteRepository = alerteRepository;
        this.pieceRepository = pieceRepository;
        this.chiffrementService = chiffrementService;
        this.passerelleSms = passerelleSms;
        this.proprietes = proprietes;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${samapiece.alerte-correspondance.intervalle-ms:10000}")
    public void traiterEnAttente() {
        notificationRepository
                .findTop50ByStatutAndProchaineTentativeLessThanEqualOrderByProchaineTentativeAsc(
                        NotificationCorrespondance.Statut.EN_ATTENTE, OffsetDateTime.now())
                .forEach(notification -> traiter(notification.getId()));
    }

    void traiter(UUID notificationId) {
        transactionTemplate.executeWithoutResult(status -> {
            NotificationCorrespondance notification = notificationRepository.findById(notificationId).orElse(null);
            if (notification == null || notification.getStatut() != NotificationCorrespondance.Statut.EN_ATTENTE) {
                return;
            }
            try {
                envoyer(notification);
            } catch (Exception exception) {
                gererEchec(notification, exception);
            }
        });
    }

    private void envoyer(NotificationCorrespondance notification) {
        Alerte alerte = alerteRepository.findById(notification.getAlerteId())
                .orElseThrow(() -> new IllegalStateException("Alerte introuvable pour la notification de correspondance."));
        if (!alerte.isActive()) {
            LOG.info("Alerte {} desinscrite avant l'envoi, notification annulee.", notification.getAlerteId());
            notification.annuler();
            return;
        }
        Piece piece = pieceRepository.findById(notification.getPieceId())
                .orElseThrow(() -> new IllegalStateException("Piece introuvable pour la notification de correspondance."));
        byte[] contactClair = chiffrementService.dechiffrer(alerte.getContactChiffre(), alerte.getContactIv());
        NumeroTelephone destinataire = NumeroTelephone.de(new String(contactClair, StandardCharsets.UTF_8));
        passerelleSms.envoyer(destinataire, construireMessage(piece));
        notification.marquerEnvoyee();
    }

    private void gererEchec(NotificationCorrespondance notification, Exception exception) {
        int tentativeSuivante = notification.getNombreTentatives() + 1;
        if (tentativeSuivante > proprietes.getMaxTentatives()) {
            LOG.error(
                    "Nombre maximal de tentatives ({}) atteint pour l'alerte {} / piece {}, notification en echec definitif",
                    proprietes.getMaxTentatives(),
                    notification.getAlerteId(),
                    notification.getPieceId(),
                    exception);
            notification.marquerEchec();
            return;
        }
        long delaiMs = delaiAvantTentative(tentativeSuivante);
        LOG.warn(
                "Nouvel echec pour l'alerte {} / piece {}, nouvelle tentative dans {} ms (tentative {})",
                notification.getAlerteId(),
                notification.getPieceId(),
                delaiMs,
                tentativeSuivante,
                exception);
        notification.reprogrammer(OffsetDateTime.now().plusNanos(delaiMs * 1_000_000L));
    }

    private long delaiAvantTentative(int tentative) {
        return switch (tentative) {
            case 1 -> proprietes.getRetryDelai30sMs();
            case 2 -> proprietes.getRetryDelai2mMs();
            default -> proprietes.getRetryDelai10mMs();
        };
    }

    private String construireMessage(Piece piece) {
        return "Une piece correspondant a votre alerte est disponible (fiche " + piece.getNumeroFiche()
                + ") au poste " + piece.getPoste().getNom() + ". Presentez-vous avec une piece d'identite.";
    }
}
