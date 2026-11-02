#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"

/*
 * Chamar no começo do boot. Se esta versão acabou de ser instalada (PENDING_VERIFY),
 * arma o timer que reverte para a anterior caso o autoteste não passe a tempo.
 */
void boot_check_begin(void);

/* Esta versão ainda está em teste? */
bool boot_check_pending_verify(void);

/* Autoteste passou (Wi-Fi + check-in com 200): confirma a versão e desarma o timer. */
void boot_check_self_test_passed(void);

/*
 * Se existe um relatório pendente na NVS, envia CONFIRMED ou ROLLED_BACK.
 * Só apaga o pendente depois que o servidor responder 2xx; senão tenta no próximo ciclo.
 */
void boot_check_report_pending(void);

/* Gravado pelo updater antes de trocar a partição de boot. */
esp_err_t boot_check_save_pending(uint32_t release_id, const char *expected_version, const char *from_version);
void boot_check_clear_pending(void);
