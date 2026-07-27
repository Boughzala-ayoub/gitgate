package dev.gitgate.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A first-class repository in the multi-tenant model: it has an owner and a visibility.
 * The on-disk bare git repo is still keyed by {@link #name}; this row carries who controls it.
 */
@Entity
@Table(name = "repo")
public class Repo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String name;

    @ManyToOne
    @JoinColumn(name = "owner_id")
    private AppUser owner;

    /** PRIVATE for now; PUBLIC arrives in Phase 8 (anonymous access). */
    @Column(nullable = false)
    private String visibility = "PRIVATE";

    private Instant createdAt;

    protected Repo() {
    }

    public Repo(String name, AppUser owner) {
        this.name = name;
        this.owner = owner;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public AppUser getOwner() {
        return owner;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }
}
