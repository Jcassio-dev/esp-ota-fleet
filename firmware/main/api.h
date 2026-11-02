#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "esp_err.h"

/* Oferta de atualização recebida no check-in. */
typedef struct {
    uint32_t release_id;
    char version[32];
    size_t size;
    char sha256[65];
    char signature[160]; /* base64 do DER; ECDSA P-256 cabe em 96 caracteres */
    char url[128];       /* caminho relativo, ex.: /api/releases/7/firmware */
    size_t chunk_size;
} ota_offer_t;

/* "esp32-" + MAC da interface Wi-Fi em hex. */
const char *api_device_id(void);

/* URL absoluta a partir de um caminho do servidor. */
void api_url(char *out, size_t out_len, const char *path);

/*
 * Faz o check-in. Devolve ESP_OK só quando o servidor respondeu 200 com JSON válido;
 * nesse caso *has_offer diz se veio atualização.
 */
esp_err_t api_checkin(const char *version, int battery, bool *has_offer, ota_offer_t *offer);

/*
 * Envia um evento (DOWNLOADING, INSTALLED, CONFIRMED, ROLLED_BACK, FAILED).
 * Devolve o status HTTP, ou -1 se não chegou ao servidor. detail pode ser NULL.
 */
int api_send_event(uint32_t release_id, const char *type, const char *detail);
