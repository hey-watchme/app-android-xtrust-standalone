package com.xtrust.standalone.util

import android.util.Log

object AppLog {
    private const val ENABLE_DEBUG_LOGS = false

    fun d(tag: String, message: String) {
        if (ENABLE_DEBUG_LOGS) {
            Log.d(tag, message)
        }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.w(tag, message)
        } else {
            Log.w(tag, message, throwable)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.e(tag, message)
        } else {
            Log.e(tag, message, throwable)
        }
    }
}
