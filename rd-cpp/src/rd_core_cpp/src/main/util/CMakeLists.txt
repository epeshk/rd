add_library(rd_core_cpp_util STATIC
        core_util.h erase_if.h gen_util.h overloaded.h shared_function.h
        core_traits.h
        filesystem.cpp filesystem.h
        enum.cpp enum.h)

target_include_directories(rd_core_cpp_util PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(rd_core_cpp_util rd_core_cpp)