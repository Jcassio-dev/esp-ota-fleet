cmake_minimum_required(VERSION 3.16)

# Versão gravada no esp_app_desc_t da imagem. É dela que o servidor e o próprio
# aparelho leem a versão. Para gerar outra: idf.py -DPROJECT_VER=1.1.0 build
if(NOT DEFINED PROJECT_VER)
    set(PROJECT_VER "1.0.0")
endif()

# Camadas de configuração: padrão do projeto + sdkconfig.local (Wi-Fi e URL, fora do git).
# O build-versions.sh acrescenta sdkconfig.broken para gerar a versão quebrada de propósito.
if(NOT DEFINED SDKCONFIG_DEFAULTS)
    set(SDKCONFIG_DEFAULTS "sdkconfig.defaults")
    if(EXISTS "${CMAKE_CURRENT_LIST_DIR}/sdkconfig.local")
        list(APPEND SDKCONFIG_DEFAULTS "sdkconfig.local")
    endif()
endif()

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(ota-fleet-device)
