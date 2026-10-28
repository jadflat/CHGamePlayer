/* jni_md.h for Android arm64 (LP64), used with the JDK's jni.h: zig has no NDK headers */
#ifndef _JAVASOFT_JNI_MD_H_
#define _JAVASOFT_JNI_MD_H_

#define JNIEXPORT __attribute__((visibility("default")))
#define JNIIMPORT __attribute__((visibility("default")))
#define JNICALL

typedef int jint;
typedef long jlong;
typedef signed char jbyte;

#endif
