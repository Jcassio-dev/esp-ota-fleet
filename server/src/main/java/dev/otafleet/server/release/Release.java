package dev.otafleet.server.release;

import dev.otafleet.core.SemVer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "release")
public class Release {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String version;

    @Column(name = "project_name", nullable = false)
    private String projectName;

    @Column(name = "size_bytes", nullable = false)
    private long size;

    @Column(nullable = false)
    private String sha256;

    @Column(nullable = false)
    private String signature;

    @Column(name = "storage_path", nullable = false)
    private String storagePath;

    @Column(name = "rollout_percent", nullable = false)
    private int rolloutPercent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReleaseStatus status;

    @Column(name = "pause_reason")
    private String pauseReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Release() {
    }

    public Release(String version, String projectName, long size, String sha256, String signature,
                   String storagePath, int rolloutPercent, Instant now) {
        this.version = version;
        this.projectName = projectName;
        this.size = size;
        this.sha256 = sha256;
        this.signature = signature;
        this.storagePath = storagePath;
        this.rolloutPercent = rolloutPercent;
        this.status = ReleaseStatus.ACTIVE;
        this.createdAt = now;
    }

    /** Ajusta a porcentagem e retoma a versão se ela estava pausada. */
    public void rollout(int percent) {
        this.rolloutPercent = percent;
        this.status = ReleaseStatus.ACTIVE;
        this.pauseReason = null;
    }

    public void pause(String reason) {
        this.status = ReleaseStatus.PAUSED;
        this.pauseReason = reason;
    }

    public SemVer semVer() {
        return SemVer.parse(version);
    }

    public Long getId() {
        return id;
    }

    public String getVersion() {
        return version;
    }

    public String getProjectName() {
        return projectName;
    }

    public long getSize() {
        return size;
    }

    public String getSha256() {
        return sha256;
    }

    public String getSignature() {
        return signature;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public int getRolloutPercent() {
        return rolloutPercent;
    }

    public ReleaseStatus getStatus() {
        return status;
    }

    public String getPauseReason() {
        return pauseReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
