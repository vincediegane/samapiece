package sn.samapiece.enregistrement.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreerPieceRequestTest {

    @Test
    void creerPieceRequest_neDoitExposerNiPosteIdNiAgentCreateurId() {
        RecordComponent[] composants = CreerPieceRequest.class.getRecordComponents();

        List<String> noms = List.of(
                composants[0].getName(),
                composants[1].getName(),
                composants[2].getName(),
                composants[3].getName(),
                composants[4].getName(),
                composants[5].getName(),
                composants[6].getName(),
                composants[7].getName());

        assertThat(noms).containsExactly(
                "typeDocument",
                "nomTitulaire",
                "prenomTitulaire",
                "numeroDocument",
                "dateNaissanceTitulaire",
                "dateDepot",
                "etatDocument",
                "remarques");
        assertThat(noms).doesNotContain("posteId", "agentCreateurId");
    }
}
