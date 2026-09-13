package sn.samapiece.recherche.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.samapiece.recherche.RecherchePubliqueService;

@RestController
@RequestMapping("/api/v1/recherche-publique")
public class RecherchePubliqueController {

    private final RecherchePubliqueService service;

    public RecherchePubliqueController(RecherchePubliqueService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<RecherchePubliqueResponse> rechercher(@RequestBody RecherchePubliqueRequest requete) {
        return ResponseEntity.ok(service.rechercher(requete));
    }
}
