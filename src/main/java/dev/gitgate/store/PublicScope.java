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

    protected PublicScope() {
    }

    public PublicScope(Repo repo, String pathGlob) {
        this.repo = repo;
        this.pathGlob = pathGlob;
    }

    public Long getId() {
        return id;
    }

    public String getPathGlob() {
        return pathGlob;
    }
}
