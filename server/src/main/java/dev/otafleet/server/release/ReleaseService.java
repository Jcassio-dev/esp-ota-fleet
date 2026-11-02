package dev.otafleet.server.release;

import dev.otafleet.core.AttemptState;
import dev.otafleet.core.FirmwareImage;
import dev.otafleet.core.FirmwareImage.AppDescriptor;
import dev.otafleet.core.FirmwareImage.InvalidImageException;
import dev.otafleet.core.SemVer;
import dev.otafleet.core.SignatureVerifier;
import dev.otafleet.server.attempt.UpdateAttemptRepository;
import dev.otafleet.server.attempt.UpdateAttemptRepository.StateCount;
import dev.otafleet.server.web.ApiException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReleaseService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

    private final ReleaseRepository releases;
    private final UpdateAttemptRepository attempts;
    private final FirmwareStorage storage;
    private final Optional<SignatureVerifier> verifier;
    private final Clock clock;

    public ReleaseService(ReleaseRepository releases, UpdateAttemptRepository attempts, FirmwareStorage storage,
                          Optional<SignatureVerifier> verifier, Clock clock) {
        this.releases = releases;
        this.attempts = attempts;
        this.storage = storage;
        this.verifier = verifier;
        this.clock = clock;
    }

    /**
     * Publica uma versão nova. A versão é lida de dentro da imagem, nunca de um campo do upload.
     */
    @Transactional
    public ReleaseView publish(byte[] image, String signatureBase64, String declaredSha256, int rolloutPercent) {
        if (image.length == 0) {
            throw ApiException.badRequest("Arquivo de firmware vazio");
        }
        if (rolloutPercent < 0 || rolloutPercent > 100) {
            throw ApiException.badRequest("rolloutPercent deve estar entre 0 e 100");
        }
        String sha256 = sha256Hex(image);
        if (declaredSha256 != null && !declaredSha256.isBlank() && !declaredSha256.trim().equalsIgnoreCase(sha256)) {
            throw ApiException.badRequest("SHA-256 declarado não confere com o arquivo recebido");
        }

        AppDescriptor desc;
        try {
            desc = FirmwareImage.readDescriptor(image);
        } catch (InvalidImageException e) {
            throw ApiException.badRequest("Imagem inválida: " + e.getMessage());
        }
        SemVer version = SemVer.tryParse(desc.version()).orElseThrow(() -> ApiException.badRequest(
                "Versão da imagem fora do formato MAJOR.MINOR.PATCH: " + desc.version()));
        if (releases.existsByVersion(version.toString())) {
            throw ApiException.conflict("A versão " + version + " já foi publicada");
        }

        byte[] signature = decodeSignature(signatureBase64);
        verifier.ifPresent(v -> {
            if (!v.verify(image, signature)) {
                throw ApiException.badRequest("Assinatura não confere com a chave pública configurada");
            }
        });

        Path path = storage.save(version.toString(), image);
        Release release = releases.save(new Release(version.toString(), desc.projectName(), image.length, sha256,
                Base64.getEncoder().encodeToString(signature), path.toString(), rolloutPercent, clock.instant()));
        log.info("Versão {} publicada (id {}, {} bytes, rollout {}%)",
                version, release.getId(), image.length, rolloutPercent);
        return ReleaseView.of(release, emptyCounts());
    }

    @Transactional
    public ReleaseView rollout(long id, int percent) {
        if (percent < 0 || percent > 100) {
            throw ApiException.badRequest("percent deve estar entre 0 e 100");
        }
        Release release = find(id);
        release.rollout(percent);
        log.info("Versão {} com rollout de {}%", release.getVersion(), percent);
        return view(release);
    }

    @Transactional
    public ReleaseView pause(long id, String reason) {
        Release release = find(id);
        release.pause(reason == null || reason.isBlank() ? "Pausada manualmente" : reason);
        log.info("Versão {} pausada: {}", release.getVersion(), release.getPauseReason());
        return view(release);
    }

    @Transactional(readOnly = true)
    public ReleaseView get(long id) {
        return view(find(id));
    }

    @Transactional(readOnly = true)
    public List<ReleaseView> list() {
        Map<Long, Map<AttemptState, Long>> counts = new HashMap<>();
        for (StateCount row : attempts.countByReleaseAndState()) {
            counts.computeIfAbsent(row.getReleaseId(), k -> emptyCounts()).put(row.getState(), row.getTotal());
        }
        return releases.findAllByOrderByIdDesc().stream()
                .map(r -> ReleaseView.of(r, counts.getOrDefault(r.getId(), emptyCounts())))
                .toList();
    }

    @Transactional(readOnly = true)
    public Release find(long id) {
        return releases.findById(id).orElseThrow(() -> ApiException.notFound("Versão " + id + " não encontrada"));
    }

    private ReleaseView view(Release release) {
        Map<AttemptState, Long> counts = emptyCounts();
        for (AttemptState state : AttemptState.values()) {
            counts.put(state, attempts.countByReleaseIdAndState(release.getId(), state));
        }
        return ReleaseView.of(release, counts);
    }

    private static Map<AttemptState, Long> emptyCounts() {
        Map<AttemptState, Long> counts = new EnumMap<>(AttemptState.class);
        for (AttemptState state : AttemptState.values()) {
            counts.put(state, 0L);
        }
        return counts;
    }

    private static byte[] decodeSignature(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw ApiException.badRequest("Assinatura obrigatória (campo signature, em base64)");
        }
        try {
            return Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Assinatura não está em base64 válido");
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
