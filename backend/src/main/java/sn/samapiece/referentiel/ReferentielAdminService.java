package sn.samapiece.referentiel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.samapiece.referentiel.web.CreerPosteRequest;
import sn.samapiece.referentiel.web.CreerRegionRequest;
import sn.samapiece.referentiel.web.PosteResponse;
import sn.samapiece.referentiel.web.RegionResponse;

@Service
@Transactional
public class ReferentielAdminService {

    private static final String MESSAGE_HORAIRES = "Les horaires doivent etre un objet JSON non vide.";

    private final RegionRepository regionRepository;
    private final PosteRepository posteRepository;
    private final ObjectMapper objectMapper;

    public ReferentielAdminService(
            RegionRepository regionRepository, PosteRepository posteRepository, ObjectMapper objectMapper) {
        this.regionRepository = regionRepository;
        this.posteRepository = posteRepository;
        this.objectMapper = objectMapper;
    }

    public RegionResponse creerRegion(CreerRegionRequest request) {
        String nom = request.nom().trim();
        if (regionRepository.existsByNomIgnoreCase(nom)) {
            throw new RegionDejaExistanteException();
        }
        try {
            return RegionResponse.from(regionRepository.saveAndFlush(new Region(nom)));
        } catch (DataIntegrityViolationException ex) {
            throw new RegionDejaExistanteException();
        }
    }

    @Transactional(readOnly = true)
    public List<RegionResponse> listerRegions() {
        return regionRepository.findAll(Sort.by("nom")).stream().map(RegionResponse::from).toList();
    }

    public PosteResponse creerPoste(CreerPosteRequest request) {
        Region region = regionRepository.findById(request.regionId()).orElseThrow(RegionIntrouvableException::new);
        String horaires = serialiserHoraires(request.horaires());
        String nom = request.nom().trim();
        if (posteRepository.existsByRegionIdAndNomIgnoreCase(region.getId(), nom)) {
            throw new PosteDejaExistantException();
        }
        Poste poste = new Poste(
                region,
                nom,
                request.type(),
                request.adresse().trim(),
                request.telephone().trim(),
                horaires,
                request.latitude(),
                request.longitude());
        try {
            return PosteResponse.from(posteRepository.saveAndFlush(poste));
        } catch (DataIntegrityViolationException ex) {
            throw new PosteDejaExistantException();
        }
    }

    private String serialiserHoraires(JsonNode horaires) {
        if (!horaires.isObject() || horaires.isEmpty()) {
            throw new HorairesInvalidesException(MESSAGE_HORAIRES);
        }
        try {
            return objectMapper.writeValueAsString(horaires);
        } catch (JsonProcessingException ex) {
            throw new HorairesInvalidesException(MESSAGE_HORAIRES);
        }
    }
}
