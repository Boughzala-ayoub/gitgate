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
 * A personal access token. Only the SHA-256 hash is stored; the raw secret is shown
 * once at creation and never persisted.
 */
@Entity
@Table(name = "token")
public class Token {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Column(nullable = false)
    private String tokenHash;

    private String label;

    private Instant createdAt;

    private Instant lastUsedAt;

    protected Token() {
    }

    public Token(AppUser user, String tokenHash, String label, Instant createdAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.label = label;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getLabel() {
        return label;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }
}
