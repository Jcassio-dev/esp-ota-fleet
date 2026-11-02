#include "battery.h"

/*
 * Implementação fraca: a placa de desenvolvimento não tem medição de bateria.
 * Um produto com bateria define a sua própria battery_percent() e esta é ignorada.
 */
__attribute__((weak)) int battery_percent(void)
{
    return -1;
}
