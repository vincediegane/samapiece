package sn.samapiece.enregistrement.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.samapiece.enregistrement.PieceService;

@RestController
@RequestMapping("/api/v1/pieces")
public class PieceController {

    private final PieceService pieceService;

    public PieceController(PieceService pieceService) {
        this.pieceService = pieceService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('AGENT','CHEF_POSTE')")
    public ResponseEntity<PieceResponse> creer(@Valid @RequestBody CreerPieceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pieceService.creer(request));
    }
}
