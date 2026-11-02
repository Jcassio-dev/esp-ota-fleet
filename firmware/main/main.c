/*
 * esp-ota-fleet: laço principal do aparelho.
 *
 * A cada ciclo: (1) se a versão está em teste, o check-in serve de autoteste;
 * (2) entrega o relatório pendente da última atualização; (3) faz check-in e,
 * se vier oferta, baixa, verifica e troca de partição.
 */

#include "api.h"
#include "battery.h"
#include "boot_check.h"
#include "esp_app_desc.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "led.h"
#include "nvs_flash.h"
#include "sdkconfig.h"
#include "updater.h"
#include "wifi.h"

static const char *TAG = "main";

static void init_nvs(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);
}

void app_main(void)
{
    init_nvs();
    boot_check_begin();

    const char *version = esp_app_get_description()->version;
    led_start(version);
    ESP_LOGI(TAG, "esp-ota-fleet v%s, aparelho %s", version, api_device_id());

    for (;;) {
        bool connected = wifi_is_connected() || wifi_connect(20000) == ESP_OK;

        if (connected && boot_check_pending_verify()) {
            bool has_offer;
            ota_offer_t ignored;
            if (api_checkin(version, battery_percent(), &has_offer, &ignored) == ESP_OK) {
                boot_check_self_test_passed();
            }
        }

        if (connected && !boot_check_pending_verify()) {
            boot_check_report_pending();

            bool has_offer = false;
            ota_offer_t offer;
            if (api_checkin(version, battery_percent(), &has_offer, &offer) == ESP_OK && has_offer) {
                ESP_LOGI(TAG, "Oferta: versão %s (%u bytes)", offer.version, (unsigned)offer.size);
                updater_apply(&offer); /* só retorna se falhar */
            }
        }

        /* Em teste, tenta de novo logo; o timer do autoteste decide quando desistir. */
        int wait_s = boot_check_pending_verify() ? 5 : CONFIG_OTA_FLEET_CHECKIN_INTERVAL_S;
        vTaskDelay(pdMS_TO_TICKS(wait_s * 1000));
    }
}
