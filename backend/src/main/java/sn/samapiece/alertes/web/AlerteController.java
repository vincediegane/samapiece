package sn.samapiece.alertes.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.samapiece.alertes.AlerteService;

/**
 * {@code POST /api/v1/alertes} et {@code DELETE /api/v1/alertes/{id}}, publics (sans
 * authentification). Attention : le segment {@code {id}} de la route de désinscription est le
 * jeton opaque de désinscription (base64url, 43 caractères), jamais l'identifiant UUID de
 * l'entité {@code Alerte} — voir spec #22, décision tranchée 1.
 */
@RestController
@RequestMapping("/api/v1/alertes")
public class AlerteController {

    private final AlerteService service;

    public AlerteController(AlerteService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CreerAlerteResponse> creer(@RequestBody CreerAlerteRequest requete) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.creer(requete));
    }

    /**
     * @param tokenBrut jeton de désinscription (pas l'identifiant de l'alerte)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> desinscrire(@PathVariable("id") String tokenBrut) {
        service.desinscrire(tokenBrut);
        return ResponseEntity.noContent().build();
    }
}
