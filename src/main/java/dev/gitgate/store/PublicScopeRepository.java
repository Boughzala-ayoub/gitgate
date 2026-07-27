package dev.gitgate.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PublicScopeRepository extends JpaRepository<PublicScope, Long> {
    List<PublicScope> findByRepo(Repo repo);
}
