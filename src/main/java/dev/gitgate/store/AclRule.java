package dev.gitgate.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "acl_rule")
public class AclRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "role_id")
    private Role role;

    @Column(nullable = false)
    private String pathGlob;

    private boolean canRead;

    private boolean canWrite;

    protected AclRule() {
    }

    public AclRule(Role role, String pathGlob, boolean canRead, boolean canWrite) {
        this.role = role;
        this.pathGlob = pathGlob;
        this.canRead = canRead;
        this.canWrite = canWrite;
    }

    public Long getId() {
        return id;
    }

    public Role getRole() {
        return role;
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
