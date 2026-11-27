LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c
include $(BUILD_SHARED_LIBRARY)

# Ren'Py 内置引擎原型桥接：dlopen librenpython.so 并调用其 CPython C API。
# 只在 arm64 上有对应的 librenpython.so，其余 ABI 仍编译空壳以便统一打包。
include $(CLEAR_VARS)
LOCAL_MODULE:= libengine_bridge
LOCAL_SRC_FILES:= engine_bridge.c
LOCAL_LDLIBS:= -ldl
LOCAL_CFLAGS:= -Wall -Wextra -Os -fPIC
include $(BUILD_SHARED_LIBRARY)
