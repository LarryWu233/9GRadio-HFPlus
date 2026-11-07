set(APT_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/vendor/aptdec/src)
add_executable(audio_content_android audio_content_worker.c cw_dc_blocker.cpp vendor/multimon-ng/demod_morse.c
    ${APT_ROOT}/color.c ${APT_ROOT}/dsp.c ${APT_ROOT}/filter.c ${APT_ROOT}/image.c
    ${APT_ROOT}/algebra.c ${APT_ROOT}/libs/median.c ${APT_ROOT}/util.c ${APT_ROOT}/calibration.c)
target_include_directories(audio_content_android PRIVATE ${APT_ROOT})
target_compile_options(audio_content_android PRIVATE $<$<COMPILE_LANGUAGE:C>:-std=c11> -O2)
target_link_libraries(audio_content_android m)
target_link_options(audio_content_android PRIVATE -Wl,-z,max-page-size=16384)
set_target_properties(audio_content_android PROPERTIES OUTPUT_NAME libninegradio_audio_decode.so SUFFIX "")
add_custom_command(TARGET audio_content_android POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory ${READSB_PACKAGE_DIR}
    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:audio_content_android> ${READSB_PACKAGE_DIR}/libninegradio_audio_decode.so)
