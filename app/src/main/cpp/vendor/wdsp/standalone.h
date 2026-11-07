/* Android adapter only. The xanf processing kernel is unchanged from WDSP. */
#include <stdlib.h>
#include <string.h>
#include "anf.h"
#define malloc0(n) calloc(1, (n))
#define _aligned_free(p) free(p)
/* Upstream's bypass memcpy uses complex (two doubles), not C99 complex. */
typedef struct { double re, im; } complex;
