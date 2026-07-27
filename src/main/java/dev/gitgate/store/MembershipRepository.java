package dev.gitgate.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MembershipRepository extends JpaRepository<Membership, Long> {
    Optional<Membership> findByRepoAndUser(Repo repo, AppUser user);

    List<Membership> findByRepo(Repo repo);

    List<Membership> findByUser(AppUser user);
}
