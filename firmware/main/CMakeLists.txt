if(NOT EXISTS "${CMAKE_CURRENT_LIST_DIR}/signing_key.pem")
    message(FATAL_ERROR
        "Chave pública não encontrada em firmware/main/signing_key.pem.\n"
        "Gere com: node cli/src/cli.js keygen --out keys/ --firmware firmware/main/signing_key.pem")
endif()

idf_component_register(
    SRCS "main.c" "wifi.c" "api.c" "updater.c" "boot_check.c" "battery.c" "semver.c" "led.c"
    INCLUDE_DIRS "."
    REQUIRES app_update esp_app_format esp_http_client esp_netif esp_event esp_wifi esp_timer nvs_flash mbedtls json driver
    EMBED_TXTFILES "signing_key.pem")
