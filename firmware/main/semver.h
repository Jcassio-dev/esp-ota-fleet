#pragma once

#include <stdbool.h>

/* Mesma regra do servidor: MAJOR.MINOR.PATCH, só dígitos, sem zero à esquerda. */
bool semver_parse(const char *text, unsigned out[3]);

/* Devolve >0 se a > b, 0 se iguais, <0 se a < b. Versão inválida conta como menor que qualquer válida. */
int semver_compare(const char *a, const char *b);
