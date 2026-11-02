package dev.otafleet.server.device;

import dev.otafleet.core.AttemptState;
import dev.otafleet.core.SemVer;
import dev.otafleet.server.attempt.EventService;
import dev.otafleet.server.device.CheckinService.Offer;
import dev.otafleet.server.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/devices/{deviceId}")
public class DeviceController {

    private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final CheckinService checkins;
    private final EventService events;

    public DeviceController(CheckinService checkins, EventService events) {
        this.checkins = checkins;
        this.events = events;
    }

    public record CheckinRequest(@NotBlank String version, @Min(0) @Max(100) Integer battery) {
    }

    /** {@code update} sempre aparece (às vezes {@code null}), para simplificar o parser em C. */
    public record CheckinResponse(Offer update) {
    }

    public record EventRequest(@NotNull Long releaseId, @NotNull AttemptState type, @Size(max = 500) String detail) {
    }

    @PostMapping("/checkin")
    public CheckinResponse checkin(@PathVariable String deviceId, @Valid @RequestBody CheckinRequest request) {
        validateDeviceId(deviceId);
        SemVer version = SemVer.tryParse(request.version()).orElseThrow(() -> ApiException.badRequest(
                "version deve seguir MAJOR.MINOR.PATCH"));
        return new CheckinResponse(checkins.checkin(deviceId, version, request.battery()).orElse(null));
    }

    @PostMapping("/events")
    public ResponseEntity<Void> event(@PathVariable String deviceId, @Valid @RequestBody EventRequest request) {
        validateDeviceId(deviceId);
        events.apply(deviceId, request.releaseId(), request.type(), request.detail());
        return ResponseEntity.noContent().build();
    }

    private static void validateDeviceId(String deviceId) {
        if (!DEVICE_ID.matcher(deviceId).matches()) {
            throw ApiException.badRequest("deviceId deve seguir [A-Za-z0-9_-]{1,64}");
        }
    }
}
