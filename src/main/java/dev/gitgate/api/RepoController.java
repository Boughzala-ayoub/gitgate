package dev.gitgate.api;

import dev.gitgate.acl.AclService;
import dev.gitgate.api.Dtos.AddCollaboratorReq;
import dev.gitgate.api.Dtos.CollaboratorDto;
import dev.gitgate.api.Dtos.CreateRepoReq;
import dev.gitgate.api.Dtos.MeDto;
import dev.gitgate.api.Dtos.RepoDto;
import dev.gitgate.api.Dtos.SetVisibilityReq;
import dev.gitgate.auth.BasicAuthFilter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Authenticated, user-scoped endpoints: your profile, your repos, repo creation/deletion,
 * and per-repo collaborator management. Authorization is enforced in {@link RepoService}.
 */
@RestController
@RequestMapping("/api")
public class RepoController {

    private final RepoService repoService;
    private final AclService acl;

    public RepoController(RepoService repoService, AclService acl) {
        this.repoService = repoService;
        this.acl = acl;
    }

    @GetMapping("/me")
    public MeDto me(@RequestAttribute(BasicAuthFilter.USER_ATTRIBUTE) String me) {
        return new MeDto(me, acl.isAdmin(me));
    }

    @GetMapping("/me/repos")
    public List<RepoDto> myRepos(@RequestAttribute(BasicAuthFilter.USER_ATTRIBUTE) String me) {
        return repoService.myRepos(me);
    }

    @PostMapping("/repos")
    @ResponseStatus(HttpStatus.CREATED)
    public RepoDto create(@RequestAttribute(BasicAuthFilter.USER_ATTRIBUTE) String me,
                          @RequestBody CreateRepoReq req) {
        return repoService.create(me, req.name(), req.visibility());
    }

    @GetMapping("/repos/{name}")
    public RepoDto get(@RequestAttribute(BasicAuthFilter.USER_ATTRIBUTE) String me, @PathVariable String name) {
        return repoService.get(me, name);
    }

    @DeleteMapping("/repos/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestAttribute(BasicAuthFilter.USER_ATTRIBUTE) String me, @PathVariable String name) {
        repoService.delete(me, name);
    }

    @PutMapping("/repos/{name}/visibility")
    public RepoDto setVisibility(@RequestAttribute(BasicAuthFilter.USER_ATTRIBUTE) String me,
                                 @PathVariable String name, @RequestBody SetVisibilityReq req) {
        return repoService.setVisibility(me, name, req.visibility(), req.publicPaths());
    }

    @GetMapping("/repos/{name}/collaborators")
    public List<CollaboratorDto> collaborators(@RequestAttribute(BasicAuthFilter.USER_ATTRIBUTE) String me,
                                               @PathVariable String name) {
        return repoService.collaborators(me, name);
    }

    @PostMapping("/repos/{name}/collaborators")
    @ResponseStatus(HttpStatus.CREATED)
    public CollaboratorDto addCollaborator(@RequestAttribute(BasicAuthFilter.USER_ATTRIBUTE) String me,
                                           @PathVariable String name, @RequestBody AddCollaboratorReq req) {
        return repoService.addCollaborator(me, name, req.username(), req.role(), req.scopes());
    }

    @DeleteMapping("/repos/{name}/collaborators/{username}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeCollaborator(@RequestAttribute(BasicAuthFilter.USER_ATTRIBUTE) String me,
                                   @PathVariable String name, @PathVariable String username) {
        repoService.removeCollaborator(me, name, username);
    }
}
