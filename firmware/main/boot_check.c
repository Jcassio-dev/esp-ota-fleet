#include "boot_check.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "api.h"
#include "esp_app_desc.h"
#include "esp_log.h"
#include "esp_ota_ops.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "nvs.h"
#include "sdkconfig.h"

static const char *TAG = "boot_check";
static const char *NVS_NAMESPACE = "otafleet";

static esp_timer_handle_t s_rollback_timer;
static bool s_pending_verify;

static void rollback_timeout(void *arg)
{
    ESP_LOGE(TAG, "Autoteste não terminou em %d s; voltando para a versão anterior",
             CONFIG_OTA_FLEET_SELF_TEST_TIMEOUT_S);
    esp_ota_mark_app_invalid_rollback_and_reboot();
}

void boot_check_begin(void)
{
    const esp_partition_t *running = esp_ota_get_running_partition();
    esp_ota_img_states_t state;
    if (esp_ota_get_state_partition(running, &state) != ESP_OK || state != ESP_OTA_IMG_PENDING_VERIFY) {
        return;
    }
    s_pending_verify = true;
    ESP_LOGW(TAG, "Versão %s em teste; %d s para passar no autoteste",
             esp_app_get_description()->version, CONFIG_OTA_FLEET_SELF_TEST_TIMEOUT_S);

    const esp_timer_create_args_t args = {.callback = rollback_timeout, .name = "rollback"};
    ESP_ERROR_CHECK(esp_timer_create(&args, &s_rollback_timer));
    ESP_ERROR_CHECK(esp_timer_start_once(s_rollback_timer, (uint64_t)CONFIG_OTA_FLEET_SELF_TEST_TIMEOUT_S * 1000000));

#if CONFIG_OTA_FLEET_SIMULATE_BROKEN_BUILD
    /* Demonstração: a versão "trava" antes do autoteste. No reboot, o bootloader vê que ela
     * continua em PENDING_VERIFY, marca como ABORTED e sobe a anterior. */
    ESP_LOGE(TAG, "Build quebrada de propósito (OTA_FLEET_SIMULATE_BROKEN_BUILD): travando em 3 s");
    vTaskDelay(pdMS_TO_TICKS(3000));
    abort();
#endif
}

bool boot_check_pending_verify(void)
{
    return s_pending_verify;
}

void boot_check_self_test_passed(void)
{
    if (!s_pending_verify) {
        return;
    }
    esp_timer_stop(s_rollback_timer);
    esp_timer_delete(s_rollback_timer);
    s_rollback_timer = NULL;

    esp_err_t err = esp_ota_mark_app_valid_cancel_rollback();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Não consegui marcar a versão como válida (%s)", esp_err_to_name(err));
        esp_ota_mark_app_invalid_rollback_and_reboot();
        return;
    }
    s_pending_verify = false;
    ESP_LOGI(TAG, "Autoteste passou: versão %s confirmada", esp_app_get_description()->version);
}

esp_err_t boot_check_save_pending(uint32_t release_id, const char *expected_version, const char *from_version)
{
    nvs_handle_t nvs;
    esp_err_t err = nvs_open(NVS_NAMESPACE, NVS_READWRITE, &nvs);
    if (err != ESP_OK) {
        return err;
    }
    err = nvs_set_u32(nvs, "pend_rel", release_id);
    if (err == ESP_OK) err = nvs_set_str(nvs, "pend_ver", expected_version);
    if (err == ESP_OK) err = nvs_set_str(nvs, "pend_from", from_version);
    if (err == ESP_OK) err = nvs_commit(nvs);
    nvs_close(nvs);
    return err;
}

void boot_check_clear_pending(void)
{
    nvs_handle_t nvs;
    if (nvs_open(NVS_NAMESPACE, NVS_READWRITE, &nvs) != ESP_OK) {
        return;
    }
    nvs_erase_key(nvs, "pend_rel");
    nvs_erase_key(nvs, "pend_ver");
    nvs_erase_key(nvs, "pend_from");
    nvs_commit(nvs);
    nvs_close(nvs);
}

/* Monta o detalhe do ROLLED_BACK a partir da partição que o bootloader invalidou. */
static void describe_rollback(const char *expected, const char *running, char *out, size_t out_len)
{
    const esp_partition_t *invalid = esp_ota_get_last_invalid_partition();
    esp_app_desc_t desc;
    if (invalid != NULL && esp_ota_get_partition_description(invalid, &desc) == ESP_OK) {
        snprintf(out, out_len, "versão %s em %s foi invalidada; voltou para %s", desc.version, invalid->label, running);
    } else {
        snprintf(out, out_len, "esperava %s, subiu %s", expected, running);
    }
}

void boot_check_report_pending(void)
{
    if (s_pending_verify) {
        return; /* só reporta CONFIRMED depois que o autoteste passar */
    }
    nvs_handle_t nvs;
    if (nvs_open(NVS_NAMESPACE, NVS_READONLY, &nvs) != ESP_OK) {
        return;
    }
    uint32_t release_id;
    char expected[32] = "", from[32] = "";
    size_t len_expected = sizeof(expected), len_from = sizeof(from);
    bool found = nvs_get_u32(nvs, "pend_rel", &release_id) == ESP_OK
                 && nvs_get_str(nvs, "pend_ver", expected, &len_expected) == ESP_OK;
    nvs_get_str(nvs, "pend_from", from, &len_from);
    nvs_close(nvs);
    if (!found) {
        return;
    }

    const char *running = esp_app_get_description()->version;
    char detail[160];
    const char *type;
    if (strcmp(running, expected) == 0) {
        type = "CONFIRMED";
        snprintf(detail, sizeof(detail), "atualizado de %s", from);
    } else {
        type = "ROLLED_BACK";
        describe_rollback(expected, running, detail, sizeof(detail));
    }

    int status = api_send_event(release_id, type, detail);
    if (status >= 200 && status < 300) {
        boot_check_clear_pending();
    } else if (status == 404 || status == 409) {
        /* O servidor não vai aceitar este relatório nunca; insistir só gastaria rede. */
        ESP_LOGW(TAG, "Servidor recusou %s com HTTP %d; descartando o relatório", type, status);
        boot_check_clear_pending();
    } else {
        ESP_LOGW(TAG, "Relatório %s não foi entregue; tento no próximo ciclo", type);
    }
}
