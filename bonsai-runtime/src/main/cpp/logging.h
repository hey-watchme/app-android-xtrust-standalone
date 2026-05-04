#pragma once

#include <android/log.h>

#define BONSAI_LOG_TAG "BonsaiRuntime"

#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, BONSAI_LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, BONSAI_LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, BONSAI_LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, BONSAI_LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, BONSAI_LOG_TAG, __VA_ARGS__)
