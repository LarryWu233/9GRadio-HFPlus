if(SOAPYSDR)

    if(MSVC AND NOT MSVC_VCPKG)
        find_path(SOAPYSDR_INCLUDE_DIR SoapySDR/Device.h HINTS ${POTHOSSDR_INCLUDE_DIR})
        find_library(SOAPYSDR_LIBRARY SoapySDR.lib HINTS ${POTHOSSDR_LIBRARY_DIR})
        find_file(SOAPYSDR_DLL SoapySDR.dll HINTS ${POTHOSSDR_BINARY_DIR})

        if(SOAPYSDR_INCLUDE_DIR AND SOAPYSDR_LIBRARY AND SOAPYSDR_DLL)
            Message(STATUS "SOAPYSDR: found (PothosSDR) - " ${SOAPYSDR_INCLUDE_DIR}, ${SOAPYSDR_LIBRARY}, ${SOAPYSDR_DLL})
            set(COPY_SOAPYSDR_DLL TRUE)
            add_definitions(-DHASSOAPYSDR)

            set(SOAPYSDR_INCLUDE_DIRS ${SOAPYSDR_INCLUDE_DIR})
            set(SOAPYSDR_LIBRARIES ${SOAPYSDR_LIBRARY})

        else()
            Message(STATUS "SOAPYSDR: NOT found (PothosSDR) - " ${SOAPYSDR_INCLUDE_DIR}, ${SOAPYSDR_LIBRARY}, ${SOAPYSDR_DLL})
        endif()
    else()
        pkg_check_modules(SOAPYSDR SoapySDR)

        if(SOAPYSDR_FOUND)
            message(STATUS "SOAPYSDR: found - ${SOAPYSDR_INCLUDE_DIRS}, ${SOAPYSDR_LIBRARIES}")
            add_definitions(-DHASSOAPYSDR)
        endif()
    endif()
endif()
