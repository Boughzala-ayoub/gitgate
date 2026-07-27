package dev.gitgate.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AclRuleRepository extends JpaRepository<AclRule, Long> {
    List<AclRule> findByRoleIn(Collection<Role> roles);

    List<AclRule> findByRole(Role role);
}
