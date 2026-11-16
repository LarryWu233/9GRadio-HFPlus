/* Android allocation/types only. DSP state belongs to one serial AmProcessor. */
#pragma once
#include <stdlib.h>
#include <string.h>
#include <math.h>
#define __declspec(x)
#define malloc0(n) calloc(1,(n))
#define _aligned_free(p) free(p)
#define TWOPI 6.28318530717958647692
typedef struct { double re,im; } complex;
#include "amd.h"
