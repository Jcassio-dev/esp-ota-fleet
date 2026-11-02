package dev.otafleet.server.release;

import dev.otafleet.server.web.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entrega o binário. Devolver {@code ResponseEntity<Resource>} faz o Spring MVC tratar
 * o header {@code Range} sozinho e responder {@code 206 Partial Content}.
 */
@RestController
public class FirmwareController {

    private final ReleaseService releases;

    public FirmwareController(ReleaseService releases) {
        this.releases = releases;
    }

    @GetMapping("/api/releases/{id}/firmware")
    public ResponseEntity<Resource> download(@PathVariable long id) {
        Release release = releases.find(id);
        Path path = Path.of(release.getStoragePath());
        if (!Files.isReadable(path)) {
            throw ApiException.notFound("Binário da versão " + release.getVersion() + " não encontrado no servidor");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .eTag('"' + release.getSha256() + '"')
                .body(new FileSystemResource(path));
    }
}
