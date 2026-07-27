package dev.gitgate.api;

import dev.gitgate.api.Dtos.RepoDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public discovery of public repositories. The auth filter permits this anonymously, so anyone
 * (logged in or not) can search for and find public repos.
 */
@RestController
public class ExploreController {

    private final RepoService repoService;

    public ExploreController(RepoService repoService) {
        this.repoService = repoService;
    }

    @GetMapping("/api/explore")
    public List<RepoDto> explore(@RequestParam(required = false, defaultValue = "") String q) {
        return repoService.explore(q);
    }
}
