# Find SDRplay library.
if(SDRPLAY)
    if(NOT MSVC)

        find_path(SDRPLAY_INCLUDE_DIR sdrplay_api.h)
        find_library(SDRPLAY_LIBRARY sdrplay_api)

        if(SDRPLAY_INCLUDE_DIR AND SDRPLAY_LIBRARY)
            message(STATUS "SDRPLAY: found - ${SDRPLAY_INCLUDE_DIR}, ${SDRPLAY_LIBRARY}")
            add_definitions(-DHASSDRPLAY)
            set(SDRPLAY_INCLUDE_DIRS ${SDRPLAY_INCLUDE_DIR})
            set(SDRPLAY_LIBRARIES ${SDRPLAY_LIBRARY})
        else()
            message(STATUS "SDRPLAY: not found.")
        endif()

    else()

        set(SDRPLAY_API_DIR $ENV{PROGRAMFILES}\\SDRplay\\API)
        if(CMAKE_SIZEOF_VOID_P EQUAL 8)
            set(SDRPLAY_API_LIB_DIR ${SDRPLAY_API_DIR}\\x64\\)
        elseif(CMAKE_SIZEOF_VOID_P EQUAL 4)
            set(SDRPLAY_API_LIB_DIR ${SDRPLAY_API_DIR}\\x86\\)
        endif()

        find_path(SDRPLAY_INCLUDE_DIR sdrplay_api.h HINTS ${SDRPLAY_API_DIR}\\inc)
        find_library(SDRPLAY_LIBRARY sdrplay_api.lib HINTS ${SDRPLAY_API_LIB_DIR})
        find_file(SDRPLAY_DLL sdrplay_api.dll HINTS ${SDRPLAY_API_LIB_DIR})

        if(SDRPLAY_INCLUDE_DIR AND SDRPLAY_LIBRARY AND SDRPLAY_DLL)
            message(STATUS "SDRPLAY: found - ${SDRPLAY_INCLUDE_DIR}, ${SDRPLAY_LIBRARY}, ${SDRPLAY_DLL}")
            add_definitions(-DHASSDRPLAY)
            set(COPY_SDRPLAY_DLL TRUE)
            set(SDRPLAY_INCLUDE_DIRS ${SDRPLAY_INCLUDE_DIR})
            set(SDRPLAY_LIBRARIES ${SDRPLAY_LIBRARY})
        else()
            message(STATUS "SDRPLAY: not found. Assumed directory ${SDRPLAY_API_LIB_DIR}")
        endif()

    endif()
endif()
