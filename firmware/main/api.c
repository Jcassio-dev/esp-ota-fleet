#include "api.h"

#include <stdio.h>
#include <string.h>
#include "cJSON.h"
#include "esp_http_client.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "sdkconfig.h"

static const char *TAG = "api";
static char s_device_id[24];

const char *api_device_id(void)
{
    if (s_device_id[0] == '\0') {
        uint8_t mac[6];
        esp_read_mac(mac, ESP_MAC_WIFI_STA);
        snprintf(s_device_id, sizeof(s_device_id), "esp32-%02x%02x%02x%02x%02x%02x",
                 mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
    }
    return s_device_id;
}

void api_url(char *out, size_t out_len, const char *path)
{
    const char *base = CONFIG_OTA_FLEET_SERVER_URL;
    size_t len = strlen(base);
    while (len > 0 && base[len - 1] == '/') {
        len--;
    }
    snprintf(out, out_len, "%.*s%s", (int)len, base, path);
}

/* POST com corpo JSON. Lê a resposta (se houver) em response. Devolve o status ou -1. */
static int post_json(const char *path, const char *body, char *response, size_t response_len)
{
    char url[256];
    api_url(url, sizeof(url), path);

    esp_http_client_config_t config = {
        .url = url,
        .method = HTTP_METHOD_POST,
        .timeout_ms = 10000,
    };
    esp_http_client_handle_t client = esp_http_client_init(&config);
    if (client == NULL) {
        return -1;
    }
    esp_http_client_set_header(client, "Content-Type", "application/json");

    int status = -1;
    int body_len = (int)strlen(body);
    if (esp_http_client_open(client, body_len) != ESP_OK) {
        ESP_LOGW(TAG, "Não conectou em %s", url);
        goto done;
    }
    if (esp_http_client_write(client, body, body_len) != body_len) {
        ESP_LOGW(TAG, "Falha ao enviar o corpo para %s", url);
        goto done;
    }
    esp_http_client_fetch_headers(client);
    status = esp_http_client_get_status_code(client);

    if (response != NULL && response_len > 0) {
        size_t total = 0;
        int n;
        while (total < response_len - 1
               && (n = esp_http_client_read(client, response + total, (int)(response_len - 1 - total))) > 0) {
            total += (size_t)n;
        }
        response[total] = '\0';
    }

done:
    esp_http_client_close(client);
    esp_http_client_cleanup(client);
    return status;
}

static bool copy_string(const cJSON *obj, const char *key, char *out, size_t out_len)
{
    const cJSON *item = cJSON_GetObjectItemCaseSensitive(obj, key);
    if (!cJSON_IsString(item) || strlen(item->valuestring) >= out_len) {
        return false;
    }
    strcpy(out, item->valuestring);
    return true;
}

static bool read_number(const cJSON *obj, const char *key, double *out)
{
    const cJSON *item = cJSON_GetObjectItemCaseSensitive(obj, key);
    if (!cJSON_IsNumber(item) || item->valuedouble < 0) {
        return false;
    }
    *out = item->valuedouble;
    return true;
}

static bool parse_offer(const cJSON *update, ota_offer_t *offer)
{
    double release_id, size, chunk_size;
    memset(offer, 0, sizeof(*offer));
    if (!read_number(update, "releaseId", &release_id) || !read_number(update, "size", &size)
        || !read_number(update, "chunkSize", &chunk_size)
        || !copy_string(update, "version", offer->version, sizeof(offer->version))
        || !copy_string(update, "sha256", offer->sha256, sizeof(offer->sha256))
        || !copy_string(update, "signature", offer->signature, sizeof(offer->signature))
        || !copy_string(update, "url", offer->url, sizeof(offer->url))) {
        return false;
    }
    offer->release_id = (uint32_t)release_id;
    offer->size = (size_t)size;
    offer->chunk_size = (size_t)chunk_size;
    return true;
}

esp_err_t api_checkin(const char *version, int battery, bool *has_offer, ota_offer_t *offer)
{
    *has_offer = false;

    cJSON *request = cJSON_CreateObject();
    cJSON_AddStringToObject(request, "version", version);
    if (battery >= 0) {
        cJSON_AddNumberToObject(request, "battery", battery);
    }
    char *body = cJSON_PrintUnformatted(request);
    cJSON_Delete(request);

    char path[96];
    snprintf(path, sizeof(path), "/api/devices/%s/checkin", api_device_id());
    char response[1024];
    int status = post_json(path, body, response, sizeof(response));
    cJSON_free(body);

    if (status != 200) {
        ESP_LOGW(TAG, "Check-in respondeu %d", status);
        return ESP_FAIL;
    }

    cJSON *root = cJSON_Parse(response);
    if (root == NULL) {
        ESP_LOGW(TAG, "Resposta do check-in não é JSON válido");
        return ESP_FAIL;
    }
    esp_err_t result = ESP_OK;
    const cJSON *update = cJSON_GetObjectItemCaseSensitive(root, "update");
    if (cJSON_IsObject(update)) {
        if (parse_offer(update, offer)) {
            *has_offer = true;
        } else {
            ESP_LOGW(TAG, "Oferta com campos faltando ou grandes demais");
            result = ESP_FAIL;
        }
    } else if (!cJSON_IsNull(update)) {
        ESP_LOGW(TAG, "Resposta do check-in sem o campo update");
        result = ESP_FAIL;
    }
    cJSON_Delete(root);
    return result;
}

int api_send_event(uint32_t release_id, const char *type, const char *detail)
{
    cJSON *request = cJSON_CreateObject();
    cJSON_AddNumberToObject(request, "releaseId", release_id);
    cJSON_AddStringToObject(request, "type", type);
    if (detail != NULL) {
        cJSON_AddStringToObject(request, "detail", detail);
    }
    char *body = cJSON_PrintUnformatted(request);
    cJSON_Delete(request);

    char path[96];
    snprintf(path, sizeof(path), "/api/devices/%s/events", api_device_id());
    int status = post_json(path, body, NULL, 0);
    cJSON_free(body);

    ESP_LOGI(TAG, "Evento %s (versão %lu): HTTP %d", type, (unsigned long)release_id, status);
    return status;
}
