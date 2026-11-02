#include "wifi.h"

#include <string.h>
#include "esp_event.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "sdkconfig.h"

static const char *TAG = "wifi";
static const EventBits_t CONNECTED_BIT = BIT0;

static EventGroupHandle_t s_events;
static bool s_started;

static void on_event(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_START) {
        esp_wifi_connect();
    } else if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        xEventGroupClearBits(s_events, CONNECTED_BIT);
        ESP_LOGW(TAG, "Desconectado; tentando de novo");
        esp_wifi_connect();
    } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        ip_event_got_ip_t *event = (ip_event_got_ip_t *)data;
        ESP_LOGI(TAG, "Conectado, IP " IPSTR, IP2STR(&event->ip_info.ip));
        xEventGroupSetBits(s_events, CONNECTED_BIT);
    }
}

static esp_err_t wifi_start(void)
{
    s_events = xEventGroupCreate();
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_netif_create_default_wifi_sta();

    wifi_init_config_t init = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&init));
    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, on_event, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, on_event, NULL));

    wifi_config_t config = {0};
    strlcpy((char *)config.sta.ssid, CONFIG_OTA_FLEET_WIFI_SSID, sizeof(config.sta.ssid));
    strlcpy((char *)config.sta.password, CONFIG_OTA_FLEET_WIFI_PASSWORD, sizeof(config.sta.password));

    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &config));
    ESP_ERROR_CHECK(esp_wifi_start());
    s_started = true;
    return ESP_OK;
}

esp_err_t wifi_connect(int timeout_ms)
{
    if (!s_started) {
        wifi_start();
    }
    EventBits_t bits = xEventGroupWaitBits(s_events, CONNECTED_BIT, pdFALSE, pdTRUE, pdMS_TO_TICKS(timeout_ms));
    if (!(bits & CONNECTED_BIT)) {
        ESP_LOGW(TAG, "Sem conexão em %d ms (rede \"%s\")", timeout_ms, CONFIG_OTA_FLEET_WIFI_SSID);
        return ESP_ERR_TIMEOUT;
    }
    return ESP_OK;
}

bool wifi_is_connected(void)
{
    return s_events && (xEventGroupGetBits(s_events) & CONNECTED_BIT);
}
