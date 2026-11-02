#pragma once

#include "api.h"
#include "esp_err.h"

/*
 * Baixa, verifica e instala a oferta na partição livre. Se tudo der certo, troca a
 * partição de boot e reinicia (não retorna). Em qualquer falha, aborta, envia FAILED
 * com o motivo e devolve o erro.
 */
esp_err_t updater_apply(const ota_offer_t *offer);
