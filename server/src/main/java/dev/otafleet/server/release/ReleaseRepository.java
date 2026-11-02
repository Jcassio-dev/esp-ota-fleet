package dev.otafleet.server.release;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReleaseRepository extends JpaRepository<Release, Long> {

    boolean existsByVersion(String version);

    List<Release> findByStatus(ReleaseStatus status);

    List<Release> findAllByOrderByIdDesc();
}
