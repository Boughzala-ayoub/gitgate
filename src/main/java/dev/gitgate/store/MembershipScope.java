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
 * Narrows a membership to specific paths within the repo — the file-level twist that
 * distinguishes GitGate from GitHub. A collaborator can be granted, e.g., write to only
 * {@code frontend/} and read to {@code *.md}.
 */
@Entity
@Table(name = "membership_scope")
public class MembershipScope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "membership_id")
    private Membership membership;

    @Column(nullable = false)
    private String pathGlob;

    private boolean canRead;

    private boolean canWrite;

    protected MembershipScope() {
    }

    public MembershipScope(Membership membership, String pathGlob, boolean canRead, boolean canWrite) {
        this.membership = membership;
        this.pathGlob = pathGlob;
        this.canRead = canRead;
        this.canWrite = canWrite;
    }

    public Long getId() {
        return id;
    }

    public String getPathGlob() {
        return pathGlob;
    }

    public boolean isCanRead() {
        return canRead;
    }

    public boolean isCanWrite() {
        return canWrite;
    }
}
