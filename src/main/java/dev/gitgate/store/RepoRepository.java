package dev.gitgate.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

import java.util.List;

public interface RepoRepository extends JpaRepository<Repo, Long> {
    Optional<Repo> findByName(String name);

    List<Repo> findByOwner(AppUser owner);

    List<Repo> findByVisibilityOrderByNameAsc(String visibility);
}
