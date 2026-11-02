package dev.otafleet.server.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "device")
public class Device {

    @Id
    private String id;

    @Column(name = "current_version", nullable = false)
    private String currentVersion;

    private Integer battery;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Device() {
    }

    public Device(String id, String currentVersion, Instant now) {
        this.id = id;
        this.currentVersion = currentVersion;
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    public void recordCheckin(String version, Integer battery, Instant now) {
        this.currentVersion = version;
        this.battery = battery;
        this.lastSeenAt = now;
    }

    public void confirmVersion(String version) {
        this.currentVersion = version;
    }

    public String getId() {
        return id;
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public Integer getBattery() {
        return battery;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
