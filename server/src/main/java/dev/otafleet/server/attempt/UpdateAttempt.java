package dev.otafleet.server.attempt;

import dev.otafleet.core.AttemptState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** Uma linha por aparelho × versão: o histórico daquela atualização naquele aparelho. */
@Entity
@Table(name = "update_attempt")
public class UpdateAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "release_id", nullable = false)
    private Long releaseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttemptState state;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected UpdateAttempt() {
    }

    public static UpdateAttempt offered(String deviceId, Long releaseId, Instant now) {
        UpdateAttempt attempt = new UpdateAttempt();
        attempt.deviceId = deviceId;
        attempt.releaseId = releaseId;
        attempt.state = AttemptState.OFFERED;
        attempt.createdAt = now;
        attempt.updatedAt = now;
        return attempt;
    }

    /** Transição feita pelo servidor: uma tentativa FAILED ou DOWNLOADING volta a ser oferecida. */
    public void reoffer(Instant now) {
        if (state == AttemptState.FAILED || state == AttemptState.DOWNLOADING) {
            this.state = AttemptState.OFFERED;
            this.updatedAt = now;
        }
    }

    /** Aplica um evento já validado pela máquina de estados. */
    public void apply(AttemptState reported, String detail, Instant now) {
        this.state = reported;
        this.detail = detail;
        this.updatedAt = now;
        if (reported == AttemptState.FAILED) {
            this.failureCount++;
        }
    }

    public Long getId() {
        return id;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public Long getReleaseId() {
        return releaseId;
    }

    public AttemptState getState() {
        return state;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public String getDetail() {
        return detail;
    }
}
