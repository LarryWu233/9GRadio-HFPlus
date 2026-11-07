/* Android adapter. A Kotlin monitor serializes all access and destruction;
 * upstream Windows critical sections are no-ops under this ownership model.
 * WDSP's xnob algorithm is compiled unchanged. */
#include <stdlib.h>
#include <string.h>
#include <math.h>
#define __declspec(x)
#define PORT
#define PI 3.14159265358979323846
typedef int CRITICAL_SECTION;
#define InitializeCriticalSectionAndSpinCount(p,n) ((void)0)
#define EnterCriticalSection(p) ((void)0)
#define LeaveCriticalSection(p) ((void)0)
#define DeleteCriticalSection(p) ((void)0)
#define malloc0(n) calloc(1,(n))
#define _aligned_free(p) free(p)
typedef struct { double re, im; } complex;
#include "nobII.h"
