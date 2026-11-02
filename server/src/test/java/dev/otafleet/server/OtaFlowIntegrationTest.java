package dev.otafleet.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.otafleet.core.TestImages;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OtaFlowIntegrationTest {

    private static final String TOKEN = "segredo-de-teste";
    private static final KeyPair KEYS = generateKeys();
    private static Path storage;

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        storage = Files.createTempDirectory("ota-fleet-test");
        registry.add("ota-fleet.admin-token", () -> TOKEN);
        registry.add("ota-fleet.storage-dir", storage::toString);
        registry.add("ota-fleet.signing-public-key", OtaFlowIntegrationTest::publicPem);
    }

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("delete from update_attempt");
        jdbc.update("delete from release");
        jdbc.update("delete from device");
    }

    // ---------------------------------------------------------------- fluxo completo

    @Test
    void fullUpdateUntilConfirmed() throws Exception {
        byte[] image = TestImages.build("1.1.0", "ota-fleet-device", 40_000);
        long releaseId = publish(image, 0).path("id").asLong();

        checkin("esp32-0001", "1.0.0", 80).andExpect(jsonPath("$.update").value(nullValue()));

        mvc.perform(admin(post("/api/admin/releases/{id}/rollout", releaseId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"percent\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolloutPercent").value(100));

        String offer = checkin("esp32-0001", "1.0.0", 80)
                .andExpect(jsonPath("$.update.releaseId").value(releaseId))
                .andExpect(jsonPath("$.update.version").value("1.1.0"))
                .andExpect(jsonPath("$.update.size").value(image.length))
                .andExpect(jsonPath("$.update.sha256").value(sha256(image)))
                .andExpect(jsonPath("$.update.url").value("/api/releases/" + releaseId + "/firmware"))
                .andExpect(jsonPath("$.update.chunkSize").value(16384))
                .andReturn().getResponse().getContentAsString();
        assertThat(verify(image, json.readTree(offer).path("update").path("signature").asText())).isTrue();

        event("esp32-0001", releaseId, "DOWNLOADING").andExpect(status().isNoContent());

        byte[] part = mvc.perform(get("/api/releases/{id}/firmware", releaseId)
                        .header(HttpHeaders.RANGE, "bytes=16384-32767"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 16384-32767/" + image.length))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(part).isEqualTo(Arrays.copyOfRange(image, 16384, 32768));

        byte[] tail = mvc.perform(get("/api/releases/{id}/firmware", releaseId)
                        .header(HttpHeaders.RANGE, "bytes=32768-49151"))
                .andExpect(status().isPartialContent())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(tail).isEqualTo(Arrays.copyOfRange(image, 32768, image.length));

        mvc.perform(get("/api/releases/{id}/firmware", releaseId))
                .andExpect(status().isOk())
                .andExpect(content().bytes(image));

        event("esp32-0001", releaseId, "INSTALLED").andExpect(status().isNoContent());
        event("esp32-0001", releaseId, "CONFIRMED").andExpect(status().isNoContent());

        mvc.perform(admin(get("/api/admin/devices")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("esp32-0001"))
                .andExpect(jsonPath("$[0].currentVersion").value("1.1.0"));

        mvc.perform(admin(get("/api/admin/releases/{id}", releaseId)))
                .andExpect(jsonPath("$.counts.CONFIRMED").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        checkin("esp32-0001", "1.1.0", 80).andExpect(jsonPath("$.update").value(nullValue()));
        assertThat(storage.resolve("v1.1.0.bin")).exists();
    }

    @Test
    void lostIntermediateEventsStillAllowConfirmation() throws Exception {
        long releaseId = publish(TestImages.build("1.1.0"), 100).path("id").asLong();
        checkin("esp32-0002", "1.0.0", null);
        event("esp32-0002", releaseId, "CONFIRMED").andExpect(status().isNoContent());
    }

    // ---------------------------------------------------------------- pausa automática

    @Test
    void pausesReleaseWhenTooManyRollbacks() throws Exception {
        publish(TestImages.build("1.1.0"), 100);
        long bad = publish(TestImages.build("1.2.0"), 100).path("id").asLong();

        for (int i = 0; i < 5; i++) {
            String device = "esp32-bad-" + i;
            checkin(device, "1.1.0", 90).andExpect(jsonPath("$.update.releaseId").value(bad));
            event(device, bad, "DOWNLOADING");
            event(device, bad, "INSTALLED");
            event(device, bad, i < 3 ? "ROLLED_BACK" : "CONFIRMED").andExpect(status().isNoContent());
        }

        mvc.perform(admin(get("/api/admin/releases/{id}", bad)))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.pauseReason").value(containsString("3 de 5")))
                .andExpect(jsonPath("$.counts.ROLLED_BACK").value(3));

        checkin("esp32-novo", "1.1.0", 90).andExpect(jsonPath("$.update").value(nullValue()));

        mvc.perform(admin(post("/api/admin/releases/{id}/rollout", bad))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"percent\":100}"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        checkin("esp32-bad-0", "1.1.0", 90).andExpect(jsonPath("$.update").value(nullValue()));
    }

    @Test
    void manualPause() throws Exception {
        long id = publish(TestImages.build("1.1.0"), 100).path("id").asLong();
        mvc.perform(admin(post("/api/admin/releases/{id}/pause", id))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"bug no sensor\"}"))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.pauseReason").value("bug no sensor"));
        checkin("esp32-0003", "1.0.0", null).andExpect(jsonPath("$.update").value(nullValue()));
    }

    // ---------------------------------------------------------------- eventos

    @Test
    void repeatedEventsChangeNothing() throws Exception {
        long id = publish(TestImages.build("1.1.0"), 100).path("id").asLong();
        checkin("esp32-0004", "1.0.0", null);
        event("esp32-0004", id, "FAILED").andExpect(status().isNoContent());
        event("esp32-0004", id, "FAILED").andExpect(status().isNoContent());

        Integer failures = jdbc.queryForObject(
                "select failure_count from update_attempt where device_id = 'esp32-0004'", Integer.class);
        assertThat(failures).isEqualTo(1);

        event("esp32-0004", id, "CONFIRMED").andExpect(status().isNoContent());
        event("esp32-0004", id, "CONFIRMED").andExpect(status().isNoContent());
        event("esp32-0004", id, "ROLLED_BACK").andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void failedDownloadIsReofferedUntilRetryLimit() throws Exception {
        long id = publish(TestImages.build("1.1.0"), 100).path("id").asLong();
        for (int i = 0; i < 3; i++) {
            checkin("esp32-0005", "1.0.0", null).andExpect(jsonPath("$.update.releaseId").value(id));
            event("esp32-0005", id, "FAILED").andExpect(status().isNoContent());
        }
        checkin("esp32-0005", "1.0.0", null).andExpect(jsonPath("$.update").value(nullValue()));
    }

    @Test
    void eventErrors() throws Exception {
        long id = publish(TestImages.build("1.1.0"), 100).path("id").asLong();
        event("esp32-nunca-viu", id, "DOWNLOADING").andExpect(status().isNotFound());

        checkin("esp32-0006", "1.0.0", null);
        event("esp32-0006", id, "INSTALLED").andExpect(status().isNoContent());
        event("esp32-0006", id, "OFFERED").andExpect(status().isConflict());
        event("esp32-0006", id, "DOWNLOADING").andExpect(status().isConflict());

        mvc.perform(post("/api/devices/esp32-0006/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"releaseId\":" + id + ",\"type\":\"EXPLODIU\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- regras de oferta

    @Test
    void noOfferWithLowBatteryOrDuringSwap() throws Exception {
        long first = publish(TestImages.build("1.1.0"), 100).path("id").asLong();
        checkin("esp32-0007", "1.0.0", 10).andExpect(jsonPath("$.update").value(nullValue()));

        checkin("esp32-0007", "1.0.0", 90).andExpect(jsonPath("$.update.releaseId").value(first));
        event("esp32-0007", first, "INSTALLED");
        publish(TestImages.build("1.2.0"), 100);
        checkin("esp32-0007", "1.0.0", 90).andExpect(jsonPath("$.update").value(nullValue()));
    }

    @Test
    void checkinValidation() throws Exception {
        mvc.perform(post("/api/devices/{id}/checkin", "id com espaço").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"1.0.0\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        checkin("esp32-0008", "1.0", null).andExpect(status().isBadRequest());
        checkin("esp32-0008", "1.0.0", 150).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- upload

    @Test
    void uploadValidation() throws Exception {
        byte[] image = TestImages.build("1.1.0");
        mvc.perform(upload(image, sign(image), "00".repeat(32), 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("SHA-256")));

        byte[] other = TestImages.build("1.1.1");
        mvc.perform(upload(image, sign(other), null, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("Assinatura")));

        mvc.perform(upload(image, "não-é-base64!", null, 0)).andExpect(status().isBadRequest());

        byte[] notImage = new byte[500];
        mvc.perform(upload(notImage, sign(notImage), null, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("Imagem inválida")));

        byte[] badVersion = TestImages.build("v2-beta");
        mvc.perform(upload(badVersion, sign(badVersion), null, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("MAJOR.MINOR.PATCH")));

        mvc.perform(upload(image, sign(image), sha256(image), 0)).andExpect(status().isCreated());
        mvc.perform(upload(image, sign(image), null, 0)).andExpect(status().isConflict());
    }

    @Test
    void adminRequiresToken() throws Exception {
        mvc.perform(get("/api/admin/releases"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/api/admin/releases").header("X-Admin-Token", "errado"))
                .andExpect(status().isUnauthorized());
        mvc.perform(admin(get("/api/admin/releases"))).andExpect(status().isOk());
    }

    @Test
    void unknownReleaseIs404() throws Exception {
        mvc.perform(get("/api/releases/999/firmware")).andExpect(status().isNotFound());
        mvc.perform(admin(get("/api/admin/releases/999"))).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- ajudantes

    private JsonNode publish(byte[] image, int rollout) throws Exception {
        String body = mvc.perform(upload(image, sign(image), sha256(image), rollout))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder upload(
            byte[] image, String signature, String sha256, int rollout) {
        var builder = multipart("/api/admin/releases")
                .file(new MockMultipartFile("file", "firmware.bin", "application/octet-stream", image));
        builder.param("signature", signature).param("rolloutPercent", String.valueOf(rollout));
        if (sha256 != null) {
            builder.param("sha256", sha256);
        }
        builder.header("X-Admin-Token", TOKEN);
        return builder;
    }

    private ResultActions checkin(String device, String version, Integer battery) throws Exception {
        String body = battery == null
                ? "{\"version\":\"%s\"}".formatted(version)
                : "{\"version\":\"%s\",\"battery\":%d}".formatted(version, battery);
        return mvc.perform(post("/api/devices/{id}/checkin", device)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions event(String device, long releaseId, String type) throws Exception {
        return mvc.perform(post("/api/devices/{id}/events", device).contentType(MediaType.APPLICATION_JSON)
                .content("{\"releaseId\":%d,\"type\":\"%s\"}".formatted(releaseId, type)));
    }

    private static <T extends org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> T admin(T b) {
        b.header("X-Admin-Token", TOKEN);
        return b;
    }

    private static KeyPair generateKeys() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String publicPem() {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(KEYS.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    private static String sign(byte[] data) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(KEYS.getPrivate());
        signature.update(data);
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private static boolean verify(byte[] data, String base64Signature) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initVerify(KEYS.getPublic());
        signature.update(data);
        return signature.verify(Base64.getDecoder().decode(base64Signature));
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
