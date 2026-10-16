/* minimal stand-in for <jni.h>, for host builds of jni/strobe.c */
#ifndef JNI_STUB_H
#define JNI_STUB_H
#include <stdint.h>
typedef int32_t jint;
typedef void *jobject;
#endif
