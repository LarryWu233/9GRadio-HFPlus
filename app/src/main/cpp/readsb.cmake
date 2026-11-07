# readsb demodulation/tracking runs as an isolated process with raw stdin IQ.
# Its USB backends are deliberately disabled; Android retains USB ownership.
set(READSB_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/vendor/readsb)
set(ZSTD_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/vendor/zstd/lib)
file(GLOB ZSTD_SOURCES ${ZSTD_ROOT}/common/*.c ${ZSTD_ROOT}/compress/*.c
    ${ZSTD_ROOT}/decompress/*.c ${ZSTD_ROOT}/dictBuilder/*.c)
add_library(readsb_zstd STATIC ${ZSTD_SOURCES})
target_include_directories(readsb_zstd PUBLIC ${ZSTD_ROOT})
target_compile_definitions(readsb_zstd PRIVATE ZSTD_DISABLE_ASM)
target_compile_options(readsb_zstd PRIVATE -O3)
set(READSB_SOURCES readsb.c argp.c anet.c interactive.c mode_ac.c mode_s.c comm_b.c
    json_out.c net_io.c crc.c demod_2400.c uat2esnt/uat2esnt.c uat2esnt/uat_decode.c
    stats.c cpr.c icao_filter.c track.c util.c fasthash.c convert.c sdr_ifile.c
    sdr_beast.c sdr.c ais_charset.c globe_index.c geomag.c receiver.c aircraft.c api.c threadpool.c)
list(TRANSFORM READSB_SOURCES PREPEND "${READSB_ROOT}/")
add_executable(readsb_android ${READSB_SOURCES})
target_include_directories(readsb_android PRIVATE ${READSB_ROOT})
target_compile_definitions(readsb_android PRIVATE _GNU_SOURCE DISABLE_INTERACTIVE
    MODES_READSB_VERSION="5d13a0c2-android" READSB_SHORT_VERSION="hfplus" READSB_SHORT_COMMIT="5d13a0c2"
    SC16Q11_TABLE_BITS=8)
target_compile_options(readsb_android PRIVATE -std=c11 -O3)
target_link_libraries(readsb_android readsb_zstd z m)
target_link_options(readsb_android PRIVATE -Wl,-z,max-page-size=16384)
set_target_properties(readsb_android PROPERTIES OUTPUT_NAME libninegradio_readsb.so SUFFIX "")
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(READSB_VARIANT debug)
else()
    set(READSB_VARIANT release)
endif()
set(READSB_PACKAGE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../build/generated/readsb/${READSB_VARIANT}/${ANDROID_ABI})
add_custom_command(TARGET readsb_android POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory ${READSB_PACKAGE_DIR}
    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:readsb_android> ${READSB_PACKAGE_DIR}/libninegradio_readsb.so)
