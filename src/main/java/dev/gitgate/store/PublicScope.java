package dev.gitgate.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A path glob that a PUBLIC repo exposes to anonymous (and any logged-in) readers.
 * No read/write flags — anonymous access is always read-only. A public repo with no
 * scopes is fully public ({@code **}); with scopes it is <em>partially</em> public —
 * the differentiator GitHub lacks.
 */
@Entity
@Table(name = "public_scope")
public class PublicScope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "repo_id")
    private Repo repo;

    @Column(nullable = false)
    private String pathGlob;

    /**
     * When true this glob is <em>carved out</em> of the public view instead of added to it:
     * the repo is public "except these files". The column default keeps existing databases
     * upgradable in place.
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean excluded;

    protected PublicScope() {
    }

    public PublicScope(Repo repo, String pathGlob) {
        this(repo, pathGlob, false);
    }

    public PublicScope(Repo repo, String pathGlob, boolean excluded) {
        this.repo = repo;
        this.pathGlob = pathGlob;
        this.excluded = excluded;
    }

    public boolean isExcluded() {
        return excluded;
    }

    public Long getId() {
        return id;
    }

    public String getPathGlob() {
        return pathGlob;
    }
}
