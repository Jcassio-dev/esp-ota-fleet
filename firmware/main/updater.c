#include "updater.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include "battery.h"
#include "boot_check.h"
#include "esp_app_desc.h"
#include "esp_http_client.h"
#include "esp_log.h"
#include "esp_ota_ops.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "mbedtls/base64.h"
#include "mbedtls/pk.h"
#include "mbedtls/sha256.h"
#include "sdkconfig.h"
#include "semver.h"

static const char *TAG = "updater";

#define MAX_CHUNK_SIZE   (32 * 1024)
#define DEFAULT_CHUNK    (16 * 1024)
#define CHUNK_ATTEMPTS   3

/* Chave pública embutida pelo EMBED_TXTFILES; o tamanho inclui o '\0' final. */
extern const uint8_t signing_key_pem_start[] asm("_binary_signing_key_pem_start");
extern const uint8_t signing_key_pem_end[] asm("_binary_signing_key_pem_end");

/* Destino dos bytes: só recebe blocos inteiros, e grava e atualiza o hash no mesmo passo. */
typedef struct {
    esp_ota_handle_t ota;
    mbedtls_sha256_context sha;
    size_t written;
} sink_t;

static esp_err_t commit_chunk(sink_t *sink, const uint8_t *buf, size_t len)
{
    esp_err_t err = esp_ota_write(sink->ota, buf, len);
    if (err != ESP_OK) {
        return err;
    }
    mbedtls_sha256_update(&sink->sha, buf, len);
    sink->written += len;
    return ESP_OK;
}

/* Lê exatamente len bytes do corpo. Devolve quantos conseguiu. */
static size_t read_exact(esp_http_client_handle_t client, uint8_t *buf, size_t len)
{
    size_t total = 0;
    while (total < len) {
        int n = esp_http_client_read(client, (char *)buf + total, (int)(len - total));
        if (n <= 0) {
            break;
        }
        total += (size_t)n;
    }
    return total;
}

/*
 * Busca o bloco que começa em sink->written. Com 206, o bloco vai inteiro para a RAM e
 * só então é gravado. Se o servidor ignorar o Range (200) e estivermos no offset 0,
 * lê o corpo inteiro, ainda assim um bloco completo por vez.
 */
static esp_err_t fetch_next(const char *url, size_t total_size, uint8_t *buf, size_t chunk, sink_t *sink)
{
    size_t offset = sink->written;
    size_t len = total_size - offset < chunk ? total_size - offset : chunk;

    esp_http_client_config_t config = {.url = url, .timeout_ms = 15000, .buffer_size = 2048};
    esp_http_client_handle_t client = esp_http_client_init(&config);
    if (client == NULL) {
        return ESP_ERR_NO_MEM;
    }
    char range[48];
    snprintf(range, sizeof(range), "bytes=%u-%u", (unsigned)offset, (unsigned)(offset + len - 1));
    esp_http_client_set_header(client, "Range", range);

    esp_err_t result = ESP_FAIL;
    if (esp_http_client_open(client, 0) != ESP_OK) {
        goto done;
    }
    esp_http_client_fetch_headers(client);
    int status = esp_http_client_get_status_code(client);

    if (status == 206) {
        if (read_exact(client, buf, len) == len) {
            result = commit_chunk(sink, buf, len);
        }
    } else if (status == 200 && offset == 0) {
        ESP_LOGW(TAG, "Servidor ignorou o Range; baixando o arquivo inteiro em sequência");
        result = ESP_OK;
        while (result == ESP_OK && sink->written < total_size) {
            size_t n = total_size - sink->written < chunk ? total_size - sink->written : chunk;
            result = read_exact(client, buf, n) == n ? commit_chunk(sink, buf, n) : ESP_FAIL;
        }
    } else {
        ESP_LOGW(TAG, "Download respondeu %d no offset %u", status, (unsigned)offset);
    }

done:
    esp_http_client_close(client);
    esp_http_client_cleanup(client);
    return result;
}

static bool hex_equals(const uint8_t hash[32], const char *hex)
{
    char computed[65];
    for (int i = 0; i < 32; i++) {
        snprintf(computed + i * 2, 3, "%02x", hash[i]);
    }
    return strcasecmp(computed, hex) == 0;
}

static esp_err_t verify_signature(const uint8_t hash[32], const char *signature_b64)
{
    uint8_t signature[80];
    size_t signature_len = 0;
    if (mbedtls_base64_decode(signature, sizeof(signature), &signature_len,
                              (const unsigned char *)signature_b64, strlen(signature_b64)) != 0) {
        return ESP_ERR_INVALID_ARG;
    }

    mbedtls_pk_context pk;
    mbedtls_pk_init(&pk);
    int ret = mbedtls_pk_parse_public_key(&pk, signing_key_pem_start,
                                          signing_key_pem_end - signing_key_pem_start);
    if (ret == 0) {
        ret = mbedtls_pk_verify(&pk, MBEDTLS_MD_SHA256, hash, 32, signature, signature_len);
    }
    mbedtls_pk_free(&pk);
    return ret == 0 ? ESP_OK : ESP_ERR_INVALID_CRC;
}

static esp_err_t fail(const ota_offer_t *offer, const char *reason)
{
    ESP_LOGE(TAG, "Atualização para %s falhou: %s", offer->version, reason);
    api_send_event(offer->release_id, "FAILED", reason);
    return ESP_FAIL;
}

esp_err_t updater_apply(const ota_offer_t *offer)
{
    const char *current = esp_app_get_description()->version;
    int battery = battery_percent();
    if (battery >= 0 && battery < CONFIG_OTA_FLEET_MIN_BATTERY) {
        ESP_LOGW(TAG, "Bateria em %d%%, abaixo de %d%%: adiando", battery, CONFIG_OTA_FLEET_MIN_BATTERY);
        return ESP_ERR_INVALID_STATE;
    }
    if (semver_compare(offer->version, current) <= 0) {
        return fail(offer, "versão oferecida não é maior que a atual");
    }

    const esp_partition_t *target = esp_ota_get_next_update_partition(NULL);
    if (target == NULL) {
        return fail(offer, "nenhuma partição livre para atualização");
    }
    if (offer->size == 0 || offer->size > target->size) {
        return fail(offer, "imagem maior que a partição livre");
    }

    size_t chunk = offer->chunk_size == 0 ? DEFAULT_CHUNK : offer->chunk_size;
    if (chunk > MAX_CHUNK_SIZE) {
        chunk = MAX_CHUNK_SIZE;
    }
    uint8_t *buf = malloc(chunk);
    if (buf == NULL) {
        return fail(offer, "sem memória para o buffer de download");
    }

    ESP_LOGI(TAG, "Atualizando %s → %s (%u bytes em %s, blocos de %u)",
             current, offer->version, (unsigned)offer->size, target->label, (unsigned)chunk);
    api_send_event(offer->release_id, "DOWNLOADING", NULL);

    sink_t sink = {.written = 0};
    mbedtls_sha256_init(&sink.sha);
    mbedtls_sha256_starts(&sink.sha, 0);

    char reason[96] = "";
    esp_err_t err = esp_ota_begin(target, offer->size, &sink.ota);
    if (err != ESP_OK) {
        snprintf(reason, sizeof(reason), "esp_ota_begin: %s", esp_err_to_name(err));
        mbedtls_sha256_free(&sink.sha);
        free(buf);
        return fail(offer, reason);
    }

    char url[256];
    api_url(url, sizeof(url), offer->url);

    while (sink.written < offer->size) {
        err = ESP_FAIL;
        for (int attempt = 1; attempt <= CHUNK_ATTEMPTS && err != ESP_OK; attempt++) {
            err = fetch_next(url, offer->size, buf, chunk, &sink);
            if (err != ESP_OK && attempt < CHUNK_ATTEMPTS) {
                int wait_ms = 1000 << (attempt - 1); /* 1 s, 2 s */
                ESP_LOGW(TAG, "Bloco no offset %u falhou (tentativa %d); de novo em %d ms",
                         (unsigned)sink.written, attempt, wait_ms);
                vTaskDelay(pdMS_TO_TICKS(wait_ms));
            }
        }
        if (err != ESP_OK) {
            snprintf(reason, sizeof(reason), "download parou no offset %u", (unsigned)sink.written);
            break;
        }
        ESP_LOGI(TAG, "%u / %u bytes", (unsigned)sink.written, (unsigned)offer->size);
    }
    free(buf);

    uint8_t hash[32];
    mbedtls_sha256_finish(&sink.sha, hash);
    mbedtls_sha256_free(&sink.sha);

    if (reason[0] == '\0' && !hex_equals(hash, offer->sha256)) {
        strcpy(reason, "SHA-256 não confere");
    }
    if (reason[0] == '\0' && verify_signature(hash, offer->signature) != ESP_OK) {
        strcpy(reason, "assinatura inválida");
    }
    if (reason[0] != '\0') {
        esp_ota_abort(sink.ota);
        return fail(offer, reason);
    }

    err = esp_ota_end(sink.ota); /* valida a estrutura da imagem */
    if (err != ESP_OK) {
        snprintf(reason, sizeof(reason), "imagem inválida (esp_ota_end: %s)", esp_err_to_name(err));
        return fail(offer, reason);
    }

    /* Antidowngrade: a versão que vale é a de dentro da imagem assinada. */
    esp_app_desc_t desc;
    if (esp_ota_get_partition_description(target, &desc) != ESP_OK) {
        return fail(offer, "não consegui ler a descrição da imagem nova");
    }
    if (strcmp(desc.version, offer->version) != 0) {
        snprintf(reason, sizeof(reason), "imagem traz %s, oferta dizia %s", desc.version, offer->version);
        return fail(offer, reason);
    }
    if (semver_compare(desc.version, current) <= 0) {
        snprintf(reason, sizeof(reason), "downgrade recusado (%s → %s)", current, desc.version);
        return fail(offer, reason);
    }

    api_send_event(offer->release_id, "INSTALLED", NULL);

    err = boot_check_save_pending(offer->release_id, offer->version, current);
    if (err != ESP_OK) {
        snprintf(reason, sizeof(reason), "não gravou o pendente na NVS: %s", esp_err_to_name(err));
        return fail(offer, reason);
    }
    err = esp_ota_set_boot_partition(target);
    if (err != ESP_OK) {
        boot_check_clear_pending();
        snprintf(reason, sizeof(reason), "esp_ota_set_boot_partition: %s", esp_err_to_name(err));
        return fail(offer, reason);
    }

    ESP_LOGI(TAG, "Versão %s instalada em %s; reiniciando", offer->version, target->label);
    vTaskDelay(pdMS_TO_TICKS(500));
    esp_restart();
    return ESP_OK; /* não chega aqui */
}
