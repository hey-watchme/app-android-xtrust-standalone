package com.xtrust.standalone

import android.app.Application
import com.xtrust.standalone.llm.LocalLlmEngine
import kotlinx.coroutines.sync.Mutex

class XtrustApplication : Application() {
    var llmEngine: LocalLlmEngine? = null
    val llmMutex = Mutex()
}
