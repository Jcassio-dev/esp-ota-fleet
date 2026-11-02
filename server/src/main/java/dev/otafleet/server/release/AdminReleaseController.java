package dev.otafleet.server.release;

import dev.otafleet.server.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/releases")
public class AdminReleaseController {

    private final ReleaseService service;

    public AdminReleaseController(ReleaseService service) {
        this.service = service;
    }

    public record RolloutRequest(@NotNull @Min(0) @Max(100) Integer percent) {
    }

    public record PauseRequest(@Size(max = 500) String reason) {
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ReleaseView publish(@RequestParam("file") MultipartFile file,
                               @RequestParam("signature") String signature,
                               @RequestParam(value = "sha256", required = false) String sha256,
                               @RequestParam(value = "rolloutPercent", defaultValue = "0") int rolloutPercent) {
        try {
            return service.publish(file.getBytes(), signature, sha256, rolloutPercent);
        } catch (IOException e) {
            throw ApiException.badRequest("Não foi possível ler o arquivo enviado");
        }
    }

    @GetMapping
    public List<ReleaseView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ReleaseView get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping("/{id}/rollout")
    public ReleaseView rollout(@PathVariable long id, @Valid @RequestBody RolloutRequest request) {
        return service.rollout(id, request.percent());
    }

    @PostMapping("/{id}/pause")
    public ReleaseView pause(@PathVariable long id, @Valid @RequestBody(required = false) PauseRequest request) {
        return service.pause(id, request == null ? null : request.reason());
    }
}
