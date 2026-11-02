package dev.otafleet.server.release;

import dev.otafleet.server.config.OtaFleetProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.stereotype.Component;

/** Guarda os binários publicados como {@code storage-dir/v<versão>.bin}. */
@Component
public class FirmwareStorage {

    private final Path root;

    public FirmwareStorage(OtaFleetProperties props) {
        this.root = Path.of(props.storageDir()).toAbsolutePath().normalize();
    }

    public Path save(String version, byte[] bytes) {
        try {
            Files.createDirectories(root);
            Path target = root.resolve("v" + version + ".bin");
            Path temp = Files.createTempFile(root, "upload-", ".tmp");
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("não foi possível salvar o firmware " + version, e);
        }
    }
}
