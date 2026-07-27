package dev.gitgate.audit;

import dev.gitgate.store.AuditEvent;
import dev.gitgate.store.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Records and lists git access events for the admin audit log. */
@Service
public class AuditService {

    private final AuditEventRepository events;

    public AuditService(AuditEventRepository events) {
        this.events = events;
    }

    @Transactional
    public void record(String username, String repo, String action, String ref, String detail) {
        events.save(new AuditEvent(Instant.now(), username, repo, action, ref, detail));
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> recent() {
        return events.findTop200ByOrderByTsDesc();
    }
}
