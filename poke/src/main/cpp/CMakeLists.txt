cmake_minimum_required(VERSION 3.22.1)
project(pokebrain CXX)

# The Cactus 4 engine for android-arm64 is fetched by CI into cactus/ (see the workflow).
set(CACTUS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/cactus/android-arm64)
file(GLOB CACTUS_STATIC ${CACTUS_DIR}/*.a)
file(GLOB CACTUS_SHARED ${CACTUS_DIR}/*.so)

add_library(pokebrain SHARED poke_brain.cpp)
target_include_directories(pokebrain PRIVATE ${CACTUS_DIR})
find_library(log-lib log)

if(CACTUS_STATIC)
    message(STATUS "Linking Cactus static engine: ${CACTUS_STATIC}")
    target_compile_definitions(pokebrain PRIVATE POKE_HAS_CACTUS=1)
    find_package(OpenMP QUIET)
    target_link_libraries(pokebrain PRIVATE -Wl,--whole-archive ${CACTUS_STATIC} -Wl,--no-whole-archive
            ${log-lib} android m dl)
    if(OpenMP_CXX_FOUND)
        target_link_libraries(pokebrain PRIVATE OpenMP::OpenMP_CXX)
    endif()
elseif(CACTUS_SHARED)
    message(STATUS "Linking Cactus shared engine: ${CACTUS_SHARED}")
    target_compile_definitions(pokebrain PRIVATE POKE_HAS_CACTUS=1)
    target_link_libraries(pokebrain PRIVATE ${CACTUS_SHARED} ${log-lib})
else()
    message(WARNING "No Cactus engine found in ${CACTUS_DIR}; building a stub (brain disabled)")
    target_link_libraries(pokebrain PRIVATE ${log-lib})
endif()
