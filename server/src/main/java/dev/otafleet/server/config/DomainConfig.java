package dev.otafleet.server.config;

import dev.otafleet.core.OfferPolicy;
import dev.otafleet.core.PausePolicy;
import dev.otafleet.core.SignatureVerifier;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Liga as regras do core (Java puro) à configuração do Spring. */
@Configuration
public class DomainConfig {

    private static final Logger log = LoggerFactory.getLogger(DomainConfig.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    OfferPolicy offerPolicy(OtaFleetProperties props) {
        return new OfferPolicy(props.minBatteryPercent(), props.maxDownloadRetries());
    }

    @Bean
    PausePolicy pausePolicy(OtaFleetProperties props) {
        return new PausePolicy(props.rollout().minSample(), props.rollout().maxRollbackRate());
    }

    /** Sem chave configurada o bean fica nulo, e quem injeta {@code Optional<SignatureVerifier>} recebe vazio. */
    @Bean
    SignatureVerifier signatureVerifier(OtaFleetProperties props) {
        String value = props.signingPublicKey();
        if (value == null || value.isBlank()) {
            log.warn("Chave pública não configurada: o servidor não vai conferir assinaturas no upload");
            return null;
        }
        String pem = value.contains("BEGIN PUBLIC KEY") ? value : readFile(value.trim());
        log.info("Conferência de assinatura ativada no upload");
        return SignatureVerifier.fromPem(pem);
    }

    private static String readFile(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            throw new UncheckedIOException("não foi possível ler a chave pública em " + path, e);
        }
    }
}
