package dev.gitgate.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant ts;

    private String username;

    private String repo;

    private String action;

    private String ref;

    @Column(length = 1000)
    private String detail;

    protected AuditEvent() {
    }

    public AuditEvent(Instant ts, String username, String repo, String action, String ref, String detail) {
        this.ts = ts;
        this.username = username;
        this.repo = repo;
        this.action = action;
        this.ref = ref;
        this.detail = detail;
    }

    public Long getId() {
        return id;
    }

    public Instant getTs() {
        return ts;
    }

    public String getUsername() {
        return username;
    }

    public String getRepo() {
        return repo;
    }

    public String getAction() {
        return action;
    }

    public String getRef() {
        return ref;
    }

    public String getDetail() {
        return detail;
    }
}
