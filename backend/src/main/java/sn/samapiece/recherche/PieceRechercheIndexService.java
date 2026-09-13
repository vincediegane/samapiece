package sn.samapiece.recherche;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PieceRechercheIndexService {

    private static final Logger LOG = LoggerFactory.getLogger(PieceRechercheIndexService.class);
    private static final String CLE_PRIMAIRE = "id";

    private final Client client;
    private final MeilisearchProperties proprietes;
    private final ObjectMapper objectMapper;

    public PieceRechercheIndexService(Client client, MeilisearchProperties proprietes, ObjectMapper objectMapper) {
        this.client = client;
        this.proprietes = proprietes;
        this.objectMapper = objectMapper;
    }

    /** Best-effort : toute exception (Meilisearch injoignable, timeout, etc.) est loguee en ERROR, jamais propagee. */
    public void indexer(PieceRechercheDocument document) {
        try {
            Index index = client.index(proprietes.getIndexPieces());
            String documentsJson = objectMapper.writeValueAsString(List.of(document));
            index.addDocuments(documentsJson, CLE_PRIMAIRE);
        } catch (Exception e) {
            LOG.error("Echec d'indexation Meilisearch pour la piece {}", document.id(), e);
        }
    }

    /**
     * Retire le document de l'index Meilisearch pour la piece donnee. Best-effort, memes garanties
     * que {@link #indexer(PieceRechercheDocument)}.
     *
     * <p>N'est appelee par aucun code de production a ce jour : {@code Piece} n'expose aucun
     * mecanisme de changement de statut apres construction. Prete a l'emploi pour le ticket #24
     * ("Workflow de retrait"), qui l'appellera depuis son futur {@code changerStatut(...)}.
     */
    public void desindexer(UUID pieceId) {
        try {
            Index index = client.index(proprietes.getIndexPieces());
            index.deleteDocument(pieceId.toString());
        } catch (Exception e) {
            LOG.error("Echec de desindexation Meilisearch pour la piece {}", pieceId, e);
        }
    }
}
