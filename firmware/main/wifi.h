#pragma once

#include <stdbool.h>
#include "esp_err.h"

/* Inicia o Wi-Fi em modo estação e espera conseguir IP por até timeout_ms. */
esp_err_t wifi_connect(int timeout_ms);

bool wifi_is_connected(void);
