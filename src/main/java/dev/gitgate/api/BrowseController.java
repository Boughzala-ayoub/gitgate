package dev.gitgate.api;

import dev.gitgate.api.Dtos.BlobDto;
import dev.gitgate.api.Dtos.BranchesDto;
import dev.gitgate.api.Dtos.TreeEntryDto;
import dev.gitgate.auth.BasicAuthFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only browse endpoints. The auth filter permits these anonymously (for public repos),
 * so {@code user} is optional — when absent, {@link BrowseService} serves the anonymous view.
 */
@RestController
@RequestMapping("/api/browse")
public class BrowseController {

    private final BrowseService browse;

    public BrowseController(BrowseService browse) {
        this.browse = browse;
    }

    @GetMapping("/{name}/branches")
    public BranchesDto branches(@RequestAttribute(name = BasicAuthFilter.USER_ATTRIBUTE, required = false) String user,
                                @PathVariable String name) {
        return browse.branches(user, name);
    }

    @GetMapping("/{name}/tree")
    public List<TreeEntryDto> tree(@RequestAttribute(name = BasicAuthFilter.USER_ATTRIBUTE, required = false) String user,
                                   @PathVariable String name,
                                   @RequestParam(required = false) String ref,
                                   @RequestParam(required = false, defaultValue = "") String path) {
        return browse.tree(user, name, ref, path);
    }

    @GetMapping("/{name}/blob")
    public BlobDto blob(@RequestAttribute(name = BasicAuthFilter.USER_ATTRIBUTE, required = false) String user,
                        @PathVariable String name,
                        @RequestParam(required = false) String ref,
                        @RequestParam String path) {
        return browse.blob(user, name, ref, path);
    }
}
