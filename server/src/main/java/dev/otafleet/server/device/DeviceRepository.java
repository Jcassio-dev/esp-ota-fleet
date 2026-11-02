package dev.otafleet.server.device;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, String> {

    List<Device> findAllByOrderByLastSeenAtDesc();
}
