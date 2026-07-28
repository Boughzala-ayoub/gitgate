package dev.gitgate.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Links a user to a repo they collaborate on, with a base role. */
@Entity
@Table(name = "membership",
        uniqueConstraints = @UniqueConstraint(columnNames = {"repo_id", "user_id"}))
public class Membership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "repo_id")
    private Repo repo;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MembershipRole role;

    protected Membership() {
    }

    public Membership(Repo repo, AppUser user, MembershipRole role) {
        this.repo = repo;
        this.user = user;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public Repo getRepo() {
        return repo;
    }

    public AppUser getUser() {
        return user;
    }

    public MembershipRole getRole() {
        return role;
    }

    public void setRole(MembershipRole role) {
        this.role = role;
    }
}
