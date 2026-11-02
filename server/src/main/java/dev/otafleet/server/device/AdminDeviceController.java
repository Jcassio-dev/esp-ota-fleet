package dev.otafleet.server.device;

import java.time.Instant;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminDeviceController {

    private final DeviceRepository devices;

    public AdminDeviceController(DeviceRepository devices) {
        this.devices = devices;
    }

    public record DeviceView(String id, String currentVersion, Integer battery, Instant lastSeenAt) {
    }

    @GetMapping("/api/admin/devices")
    @Transactional(readOnly = true)
    public List<DeviceView> list() {
        return devices.findAllByOrderByLastSeenAtDesc().stream()
                .map(d -> new DeviceView(d.getId(), d.getCurrentVersion(), d.getBattery(), d.getLastSeenAt()))
                .toList();
    }
}
