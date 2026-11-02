#include "semver.h"

#include <ctype.h>
#include <stddef.h>

bool semver_parse(const char *text, unsigned out[3])
{
    if (text == NULL) {
        return false;
    }
    const char *p = text;
    for (int part = 0; part < 3; part++) {
        if (!isdigit((unsigned char)*p)) {
            return false;
        }
        if (*p == '0' && isdigit((unsigned char)p[1])) {
            return false; /* zero à esquerda */
        }
        unsigned value = 0;
        int digits = 0;
        while (isdigit((unsigned char)*p)) {
            if (++digits > 9) {
                return false;
            }
            value = value * 10 + (unsigned)(*p - '0');
            p++;
        }
        out[part] = value;
        if (part < 2) {
            if (*p != '.') {
                return false;
            }
            p++;
        }
    }
    return *p == '\0';
}

int semver_compare(const char *a, const char *b)
{
    unsigned va[3], vb[3];
    bool ok_a = semver_parse(a, va);
    bool ok_b = semver_parse(b, vb);
    if (!ok_a || !ok_b) {
        return (int)ok_a - (int)ok_b;
    }
    for (int i = 0; i < 3; i++) {
        if (va[i] != vb[i]) {
            return va[i] > vb[i] ? 1 : -1;
        }
    }
    return 0;
}
