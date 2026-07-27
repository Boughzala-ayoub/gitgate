package dev.gitgate.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MembershipScopeRepository extends JpaRepository<MembershipScope, Long> {
    List<MembershipScope> findByMembership(Membership membership);
}
