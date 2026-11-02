#include "led.h"

#include "driver/gpio.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "sdkconfig.h"
#include "semver.h"

static int s_blinks = 1;

static void led_task(void *arg)
{
    const gpio_num_t pin = (gpio_num_t)CONFIG_OTA_FLEET_LED_GPIO;
    for (;;) {
        for (int i = 0; i < s_blinks; i++) {
            gpio_set_level(pin, 1);
            vTaskDelay(pdMS_TO_TICKS(150));
            gpio_set_level(pin, 0);
            vTaskDelay(pdMS_TO_TICKS(250));
        }
        vTaskDelay(pdMS_TO_TICKS(2000));
    }
}

void led_start(const char *version)
{
    if (CONFIG_OTA_FLEET_LED_GPIO < 0) {
        return;
    }
    unsigned v[3];
    if (semver_parse(version, v)) {
        s_blinks = (int)(v[1] % 10) + 1;
    }
    gpio_reset_pin((gpio_num_t)CONFIG_OTA_FLEET_LED_GPIO);
    gpio_set_direction((gpio_num_t)CONFIG_OTA_FLEET_LED_GPIO, GPIO_MODE_OUTPUT);
    xTaskCreate(led_task, "led", 2048, NULL, 1, NULL);
}
