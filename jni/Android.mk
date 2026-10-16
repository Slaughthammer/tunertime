LOCAL_PATH := $(call my-dir)


include $(CLEAR_VARS)

LOCAL_MODULE    := strobe-jni
LOCAL_SRC_FILES := interface.c strobe.c fft.c window.c pitch.c record.c
LOCAL_CFLAGS	:= -O -Wall
# 16 KB page alignment: Android 15+ devices may run with 16 KB pages, and
# Google Play requires it for targetSdk 35+.  Newer NDKs (r28+) do this by
# default; older ones need the flag.
LOCAL_LDFLAGS	+= -Wl,-z,max-page-size=16384

include $(BUILD_SHARED_LIBRARY)
