/* Android process/PCM adapter for pinned ggmorse. DSP and Morse interpretation
 * remain upstream. Software narrowband CW retains the existing multimon path. */
#include "ggmorse/ggmorse.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <unistd.h>

extern "C" int cw_audio_decode(int wpm) {
    // Upstream also prints received letters to stdout. Keep that diagnostic output
    // off our framed IPC without patching the library or its decoding algorithm.
    FILE* ipc=fdopen(dup(STDOUT_FILENO),"w");if(!ipc)return 4;
    if(dup2(STDERR_FILENO,STDOUT_FILENO)<0){fclose(ipc);return 4;}
    auto parameters=GGMorse::getDefaultParameters();
    parameters.sampleRateInp=48000;parameters.sampleFormatInp=GGMORSE_SAMPLE_FORMAT_F32;
    GGMorse decoder(parameters);
    auto options=GGMorse::getDefaultParametersDecode();options.speed_wpm=wpm>0?wpm:-1;
    if(!decoder.setParametersDecode(options)){fclose(ipc);return 4;}
    uint64_t samples=0,lastStatus=0;
    auto publish=[&](bool force) {
        GGMorse::TxRx text;
        if(decoder.takeRxData(text)>0) {
            fputs("T\t",ipc);for(auto c:text)fprintf(ipc,"%02x",unsigned(c));fputc('\n',ipc);fflush(ipc);
        }
        if(force || samples-lastStatus>=48000) {
            lastStatus=samples;const auto& s=decoder.getStatistics();
            fprintf(ipc,"G\t%.1f\t%.1f\n",s.estimatedPitch_Hz,s.estimatedSpeed_wpm);fflush(ipc);
        }
    };
    bool eof=false;size_t tail=3*48000*sizeof(float);
    // Same three-second EOF drain used by upstream's file example. These are
    // decoder look-ahead samples, never inserted into playback or recordings.
    decoder.decode([&](void* data,uint32_t requested)->uint32_t {
        publish(false);
        if(eof && tail==0)return 0;
        auto count=eof?0:std::fread(data,1,requested,stdin);samples+=count/sizeof(float);
        if(count<requested) {
            eof=true;if(std::ferror(stdin))return 0;
            auto missing=requested-count;
            if(tail==0 && count==0)return 0;
            std::memset(static_cast<char*>(data)+count,0,missing);tail-=std::min(tail,missing);
        }
        return requested;
    });
    publish(true);fclose(ipc);return std::ferror(stdin)?3:0;
}
