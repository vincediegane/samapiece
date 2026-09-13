package sn.samapiece.recherche;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PieceRechercheIndexServiceTest {

    private final Client client = mock(Client.class);
    private final Index index = mock(Index.class);
    private final MeilisearchProperties proprietes = new MeilisearchProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final PieceRechercheIndexService service =
            new PieceRechercheIndexService(client, proprietes, objectMapper);

    private PieceRechercheDocument document(UUID id) {
        return new PieceRechercheDocument(id, "CNI", "Fall", "Moussa", "Commissariat Central Dakar", "DISPONIBLE");
    }

    @Test
    void indexer_devraitAppelerAddDocumentsAvecLaClePrimaireId() throws Exception {
        proprietes.setIndexPieces("pieces");
        when(client.index("pieces")).thenReturn(index);
        UUID pieceId = UUID.randomUUID();

        service.indexer(document(pieceId));

        verify(index).addDocuments(contains(pieceId.toString()), org.mockito.ArgumentMatchers.eq("id"));
    }

    @Test
    void indexer_quandMeilisearchIndisponible_neDevraitPasPropagerException() throws Exception {
        proprietes.setIndexPieces("pieces");
        when(client.index("pieces")).thenReturn(index);
        when(index.addDocuments(anyString(), anyString())).thenThrow(new MeilisearchException("indisponible"));

        assertThatCode(() -> service.indexer(document(UUID.randomUUID()))).doesNotThrowAnyException();
    }

    @Test
    void desindexer_devraitAppelerDeleteDocumentAvecIdDeLaPiece() throws Exception {
        proprietes.setIndexPieces("pieces");
        when(client.index("pieces")).thenReturn(index);
        UUID pieceId = UUID.randomUUID();

        service.desindexer(pieceId);

        verify(index).deleteDocument(pieceId.toString());
    }

    @Test
    void desindexer_quandMeilisearchIndisponible_neDevraitPasPropagerException() throws Exception {
        proprietes.setIndexPieces("pieces");
        when(client.index("pieces")).thenReturn(index);
        when(index.deleteDocument(anyString())).thenThrow(new MeilisearchException("indisponible"));

        assertThatCode(() -> service.desindexer(UUID.randomUUID())).doesNotThrowAnyException();
    }
}
