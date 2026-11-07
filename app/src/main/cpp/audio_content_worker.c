/* Application IPC adapter. Decoder algorithms remain in their upstream files. */
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <signal.h>
#include <unistd.h>
#include <sys/prctl.h>
#include "vendor/multimon-ng/multimon.h"
#include "vendor/aptdec/src/apt.h"

extern const struct demod_param demod_morse;
extern int cw_dit_length, cw_gap_length;
extern bool cw_disable_auto_timing;
extern void cw_remove_dc(short *pcm, int count);

void _verbprintf(int level, const char *fmt, ...) {
    if (level) return;
    char text[2048];
    va_list args; va_start(args, fmt); vsnprintf(text, sizeof(text), fmt, args); va_end(args);
    fputs("T\t", stdout);
    for (const unsigned char *p = (unsigned char *)text; *p; ++p) printf("%02x", *p);
    putchar('\n'); fflush(stdout);
}
static int samples(void *context, float *dest, int count) {
    return (int)fread(dest, sizeof(float), count, (FILE *)context);
}
int main(int argc, char **argv) {
    prctl(PR_SET_PDEATHSIG, SIGTERM);
    if (getppid() == 1) return 1;
    if (argc < 2) return 2;
    if (!strcmp(argv[1], "--cw")) {
        if (argc > 2 && atoi(argv[2]) > 0) {
            cw_dit_length = cw_gap_length = 1200 / atoi(argv[2]);
            cw_disable_auto_timing = true;
        }
        struct demod_state state = {0}; state.dem_par = &demod_morse;
        demod_morse.init(&state);
        short pcm[1024]; size_t count; unsigned elapsed = 0;
        while ((count = fread(pcm, sizeof(short), 1024, stdin)) > 0) {
            cw_remove_dc(pcm, (int)count);
            buffer_t buffer = {.sbuffer = pcm, .fbuffer = NULL};
            demod_morse.demod(&state, buffer, (int)count);
            if ((elapsed += count) >= 22050) {
                elapsed = 0;
                printf("S\t%d\t%d\t%d\n", state.l1.morse.time_unit_dit_dah_samples,
                    (int)state.l1.morse.decoded_chars, (int)state.l1.morse.erroneous_chars); fflush(stdout);
            }
        }
        demod_morse.deinit(&state); return ferror(stdin) ? 3 : 0;
    }
    if (!strcmp(argv[1], "--apt")) {
        if (apt_init(48000)) return 4;
        float row[APT_PROW_WIDTH]; int zenith = 0;
        for (unsigned n = 0; n < APT_MAX_HEIGHT; ++n) {
            if (!apt_getpixelrow(row, n, &zenith, n == 0, samples, stdin)) break;
            /* Android ABIs are little endian. Each record is row index + 2080 float pixels. */
            if (fwrite(&n, sizeof(n), 1, stdout) != 1 ||
                fwrite(row, sizeof(float), APT_IMG_WIDTH, stdout) != APT_IMG_WIDTH) return 5;
            fflush(stdout);
        }
        return ferror(stdin) ? 3 : 0;
    }
    return 2;
}
