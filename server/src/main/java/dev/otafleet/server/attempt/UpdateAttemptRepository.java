package dev.otafleet.server.attempt;

import dev.otafleet.core.AttemptState;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UpdateAttemptRepository extends JpaRepository<UpdateAttempt, Long> {

    Optional<UpdateAttempt> findByDeviceIdAndReleaseId(String deviceId, Long releaseId);

    List<UpdateAttempt> findByDeviceId(String deviceId);

    long countByReleaseIdAndState(Long releaseId, AttemptState state);

    @Query("select a.releaseId as releaseId, a.state as state, count(a) as total "
            + "from UpdateAttempt a group by a.releaseId, a.state")
    List<StateCount> countByReleaseAndState();

    interface StateCount {
        Long getReleaseId();

        AttemptState getState();

        long getTotal();
    }
}
