package sn.samapiece.referentiel.web;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.samapiece.referentiel.PosteRepository;

@RestController
@RequestMapping("/api/v1/postes")
public class PosteController {

    private final PosteRepository posteRepository;

    public PosteController(PosteRepository posteRepository) {
        this.posteRepository = posteRepository;
    }

    @GetMapping
    public List<PosteResponse> lister() {
        return posteRepository.findAll().stream().map(PosteResponse::from).toList();
    }
}
