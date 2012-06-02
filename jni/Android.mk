LOCAL_PATH := $(call my-dir)
 
include $(CLEAR_VARS)
 
LOCAL_LDLIBS := -llog
LOCAL_MODULE    := rscamera
LOCAL_SRC_FILES := yuv2rgb.c
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
    LOCAL_CFLAGS := -DHAVE_NEON=1
	LOCAL_ARM_NEON  := true
endif 
include $(BUILD_SHARED_LIBRARY)