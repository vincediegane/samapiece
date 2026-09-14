package sn.samapiece.enregistrement.web;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.samapiece.audit.ActionAuditee;
import sn.samapiece.enregistrement.PieceService;
import sn.samapiece.enregistrement.PieceService.RecuPdf;

@RestController
@RequestMapping("/api/v1/pieces")
public class PieceController {

    private final PieceService pieceService;

    public PieceController(PieceService pieceService) {
        this.pieceService = pieceService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")
    @ActionAuditee(action = "PIECE_CREEE", entiteCible = "PIECE")
    public ResponseEntity<PieceResponse> creer(@Valid @RequestBody CreerPieceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pieceService.creer(request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")
    @ActionAuditee(action = "PIECE_CONSULTEE", entiteCible = "PIECE")
    public ResponseEntity<PieceResponse> consulter(@PathVariable UUID id) {
        return ResponseEntity.ok(pieceService.consulter(id));
    }

    @PostMapping("/{id}/retrait")
    @PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")
    @ActionAuditee(action = "PIECE_RETIREE", entiteCible = "PIECE")
    public ResponseEntity<PieceResponse> retirer(
            @PathVariable UUID id, @Valid @RequestBody RetraitRequest request) {
        return ResponseEntity.ok(pieceService.retirer(id, request));
    }

    @PostMapping("/{id}/signaler")
    @PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")
    @ActionAuditee(action = "PIECE_SIGNALEE", entiteCible = "PIECE")
    public ResponseEntity<PieceResponse> signaler(
            @PathVariable UUID id, @Valid @RequestBody SignalerRequest request) {
        return ResponseEntity.ok(pieceService.signaler(id, request));
    }

    @GetMapping("/{id}/recu")
    @PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")
    @ActionAuditee(action = "PIECE_RECU_GENERE", entiteCible = "PIECE")
    public ResponseEntity<byte[]> genererRecu(@PathVariable UUID id) {
        RecuPdf recu = pieceService.genererRecu(id);
        String nomFichier = recu.numeroFiche().replaceAll("[^A-Za-z0-9-]", "_") + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + nomFichier + "\"")
                .body(recu.contenu());
    }

    @PostMapping("/{id}/debloquer")
    @PreAuthorize("hasAnyRole('CHEF_POSTE','ADMIN_REGIONAL','ADMIN_NATIONAL')")
    @ActionAuditee(action = "PIECE_DEBLOQUEE", entiteCible = "PIECE")
    public ResponseEntity<PieceResponse> debloquer(
            @PathVariable UUID id, @Valid @RequestBody DeblocageRequest request) {
        return ResponseEntity.ok(pieceService.debloquer(id, request));
    }
}
