package sn.samapiece.enregistrement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import sn.samapiece.referentiel.Poste;
import sn.samapiece.referentiel.PosteRepository;
import sn.samapiece.referentiel.Region;
import sn.samapiece.referentiel.RegionRepository;
import sn.samapiece.referentiel.TypePoste;

@SpringBootTest
@Testcontainers
class PieceNumeroFicheGeneratorTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String HORAIRES = "{\"lundi\":{\"ouvert\":true,\"debut\":\"08:00\",\"fin\":\"18:00\"}}";

    @Autowired
    private PieceNumeroFicheGenerator numeroFicheGenerator;

    @Autowired
    private PosteRepository posteRepository;

    @Autowired
    private RegionRepository regionRepository;

    @BeforeEach
    void nettoyer() {
        posteRepository.deleteAll();
        regionRepository.deleteAll();
    }

    private Poste creerPoste() {
        Region region = regionRepository.save(new Region("Dakar"));
        return posteRepository.save(new Poste(
                region,
                "Commissariat Central Dakar",
                TypePoste.POLICE,
                "Place de l'Indépendance, Dakar",
                "+221338210000",
                HORAIRES,
                14.6928,
                -17.4467));
    }

    @Test
    void genererNumeroFiche_appeleConcurrentDeuxFois_shouldRetournerDeuxNumerosDistincts() throws Exception {
        Poste poste = creerPoste();
        LocalDate dateDepot = LocalDate.of(2026, 9, 13);
        CyclicBarrier barriere = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<String> tache = () -> {
            barriere.await();
            return numeroFicheGenerator.genererNumeroFiche(poste.getId(), dateDepot);
        };

        try {
            Future<String> future1 = executor.submit(tache);
            Future<String> future2 = executor.submit(tache);

            String numero1 = future1.get();
            String numero2 = future2.get();

            assertThat(numero1).isNotEqualTo(numero2);
            assertThat(java.util.Set.of(numero1, numero2)).allSatisfy(
                    numero -> assertThat(numero).matches("^PC-[0-9A-F]{8}-\\d{4}-\\d{5}$"));
            assertThat(java.util.List.of(numero1, numero2))
                    .extracting(numero -> numero.substring(numero.length() - 5))
                    .containsExactlyInAnyOrder("00001", "00002");
        } finally {
            executor.shutdown();
        }
    }
}
