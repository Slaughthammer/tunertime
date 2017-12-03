LOCAL_PATH := $(call my-dir)


include $(CLEAR_VARS)

LOCAL_MODULE    := strobe-jni
LOCAL_SRC_FILES := interface.c strobe.c fft.c window.c
LOCAL_CFLAGS	:= -O -Wall

include $(BUILD_SHARED_LIBRARY)
