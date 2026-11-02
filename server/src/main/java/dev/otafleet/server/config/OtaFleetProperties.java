package dev.otafleet.server.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ota-fleet")
public record OtaFleetProperties(
        String adminToken,
        @NotBlank String storageDir,
        String signingPublicKey,
        @DefaultValue("16384") @Min(1024) int chunkSize,
        @DefaultValue("25") @Min(0) @Max(100) int minBatteryPercent,
        @DefaultValue("3") @Min(1) int maxDownloadRetries,
        @Valid @DefaultValue Rollout rollout) {

    public record Rollout(
            @DefaultValue("5") @Min(1) int minSample,
            @DefaultValue("0.2") @DecimalMin("0.0") @DecimalMax("1.0") double maxRollbackRate) {
    }
}
