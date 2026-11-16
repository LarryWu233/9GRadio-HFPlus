# Find NMEA2000 support on Linux
if(NOT MSVC AND NMEA2000)

    check_include_file("linux/can.h" HAVE_CAN_HEADER)
    check_include_file("linux/can/raw.h" HAVE_RAW_CAN_HEADER)

    if(HAVE_CAN_HEADER AND HAVE_RAW_CAN_HEADER)
        find_file(NMEA2000_LIB NAMES libnmea2000.a PATHS ${NMEA2000_PATH} ${NMEA2000_PATH}/src ${NMEA2000_PATH}/NMEA2000/src)
        find_path(NMEA2000_INCLUDE NAMES NMEA2000.h PATHS PATHS ${NMEA2000_PATH} ${NMEA2000_PATH}/src ${NMEA2000_PATH}/NMEA2000/src)

        if(NMEA2000_LIB AND NMEA2000_INCLUDE)
            add_definitions(-DHASNMEA2000)
            set(NMEA2000_INCLUDE_DIRS ${NMEA2000_INCLUDE})
            set(NMEA2000_LIBRARIES ${NMEA2000_LIB})
            message(STATUS "NMEA2000: found ${NMEA2000_LIBRARIES} ${NMEA2000_INCLUDE_DIRS}")
        else()
            message(STATUS "NMEA2000: not found  ${NMEA2000_LIB} ${NMEA2000_INCLUDE}")
        endif()
    else()
        message(STATUS "Required headers (can.h and raw/can.h) not found. ${HAVE_CAN_HEADER} ${HAVE_RAW_CAN_HEADER}")
    endif()
endif()
