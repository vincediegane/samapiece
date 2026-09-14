package sn.samapiece.alertes;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.samapiece.enregistrement.TypeDocument;

public interface AlerteRepository extends JpaRepository<Alerte, UUID> {

    List<Alerte> findByActiveTrueAndTypeDocumentAndNomTitulaireIgnoreCase(
            TypeDocument typeDocument, String nomTitulaire);
}
