# AIS-catcher reads our owned stdin IQ; Android keeps USB/source ownership.
# Optional services/drivers are not part of this receiver integration.
foreach(feature SOAPYSDR RTLSDR RTLSDR_STATIC AIRSPY SDRPLAY AIRSPYHF HACKRF
    HYDRASDR HYDRASDR_STATIC SOXR ZLIB SAMPLERATE PSQL SQLITE OPENSSL NMEA2000
    WEBVIEWER API DELIVERY)
    set(${feature} OFF CACHE BOOL "Disabled in the embedded AIS decoder" FORCE)
endforeach()
add_subdirectory(vendor/ais-catcher ${CMAKE_CURRENT_BINARY_DIR}/ais-catcher)
set_target_properties(AIS-catcher PROPERTIES OUTPUT_NAME libninegradio_ais.so SUFFIX "")
target_link_options(AIS-catcher PRIVATE -Wl,-z,max-page-size=16384)
if(DEFINED NINEGRADIO_PACKAGE_VARIANT)
    set(AIS_VARIANT ${NINEGRADIO_PACKAGE_VARIANT})
elseif(CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(AIS_VARIANT debug)
else()
    set(AIS_VARIANT release)
endif()
# Use the established executable packaging directory for every build variant.
set(AIS_PACKAGE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../build/generated/readsb/${AIS_VARIANT}/${ANDROID_ABI})
set_target_properties(AIS-catcher PROPERTIES RUNTIME_OUTPUT_DIRECTORY ${AIS_PACKAGE_DIR})
