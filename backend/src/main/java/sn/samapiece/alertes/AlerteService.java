package sn.samapiece.alertes;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.samapiece.alertes.AlerteContactChiffrementService.ContactChiffre;
import sn.samapiece.alertes.web.CreerAlerteRequest;
import sn.samapiece.alertes.web.CreerAlerteResponse;
import sn.samapiece.enregistrement.NumeroDocumentHasher;
import sn.samapiece.enregistrement.NumeroDocumentHasher.NumeroDocumentHache;
import sn.samapiece.notifications.NumeroTelephone;
import sn.samapiece.notifications.PasserelleSms;

@Service
public class AlerteService {

    private static final String CANAL_SMS = "sms";

    private final AlerteRepository alerteRepository;
    private final AlerteDesinscriptionTokenRepository tokenRepository;
    private final AlerteContactChiffrementService chiffrementService;
    private final AlerteDesinscriptionTokenGenerator tokenGenerator;
    private final NumeroDocumentHasher numeroDocumentHasher;
    private final PasserelleSms passerelleSms;
    private final long tokenExpirationHeures;
    private final String lienBaseUrl;

    public AlerteService(
            AlerteRepository alerteRepository,
            AlerteDesinscriptionTokenRepository tokenRepository,
            AlerteContactChiffrementService chiffrementService,
            AlerteDesinscriptionTokenGenerator tokenGenerator,
            NumeroDocumentHasher numeroDocumentHasher,
            PasserelleSms passerelleSms,
            @Value("${samapiece.alerte.token-expiration-heures}") long tokenExpirationHeures,
            @Value("${samapiece.alerte.lien-desinscription-base-url}") String lienBaseUrl) {
        this.alerteRepository = alerteRepository;
        this.tokenRepository = tokenRepository;
        this.chiffrementService = chiffrementService;
        this.tokenGenerator = tokenGenerator;
        this.numeroDocumentHasher = numeroDocumentHasher;
        this.passerelleSms = passerelleSms;
        this.tokenExpirationHeures = tokenExpirationHeures;
        this.lienBaseUrl = lienBaseUrl;
    }

    @Transactional
    public CreerAlerteResponse creer(CreerAlerteRequest requete) {
        if (!requete.estSuffisant()) {
            throw new AlerteCriteresInsuffisantsException();
        }

        NumeroTelephone destinataire = NumeroTelephone.de(requete.contact());

        String numeroDocumentHash = null;
        String numeroDocumentSel = null;
        String numeroDocumentMasque = null;
        if (requete.numeroDocument() != null && !requete.numeroDocument().isBlank()) {
            NumeroDocumentHache hache = numeroDocumentHasher.hacher(requete.numeroDocument());
            numeroDocumentHash = hache.hash();
            numeroDocumentSel = hache.sel();
            numeroDocumentMasque = hache.masque();
        }

        ContactChiffre contactChiffre = chiffrementService.chiffrer(
                destinataire.valeurBrute().getBytes(StandardCharsets.UTF_8));

        Alerte alerte = new Alerte(
                requete.typeDocument(),
                requete.nomTitulaire(),
                requete.prenomTitulaire(),
                numeroDocumentHash,
                numeroDocumentSel,
                numeroDocumentMasque,
                requete.dateNaissanceTitulaire(),
                CANAL_SMS,
                contactChiffre.octetsChiffres(),
                contactChiffre.ivBase64());
        alerteRepository.saveAndFlush(alerte);

        String tokenBrut = tokenGenerator.genererBrut();
        String tokenHash = tokenGenerator.hacher(tokenBrut);
        AlerteDesinscriptionToken token = new AlerteDesinscriptionToken(
                alerte.getId(), tokenHash, OffsetDateTime.now().plusHours(tokenExpirationHeures));
        tokenRepository.save(token);

        String message = lienBaseUrl + "?token=" + tokenBrut;
        passerelleSms.envoyer(destinataire, message);

        return CreerAlerteResponse.confirmee();
    }

    @Transactional
    public void desinscrire(String tokenBrut) {
        if (tokenBrut == null || tokenBrut.isBlank()) {
            throw new AlerteIntrouvableOuExpireeException();
        }

        String tokenHash = tokenGenerator.hacher(tokenBrut);
        AlerteDesinscriptionToken token = tokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(AlerteIntrouvableOuExpireeException::new);

        if (!token.estValide(OffsetDateTime.now())) {
            throw new AlerteIntrouvableOuExpireeException();
        }

        Alerte alerte = alerteRepository.findById(token.getAlerteId())
                .orElseThrow(AlerteIntrouvableOuExpireeException::new);

        alerte.desinscrire();
        token.consommer();
    }
}
