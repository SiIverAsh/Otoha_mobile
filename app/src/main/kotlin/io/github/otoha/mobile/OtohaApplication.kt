package io.github.otoha.mobile

import android.app.Application
import io.github.otoha.core.Asmr18Client
import io.github.otoha.core.AsmrOneClient
import io.github.otoha.core.SourceClient
import io.github.otoha.core.SourceHttp
import io.github.otoha.core.SourceId

class OtohaApplication : Application() {
    val store by lazy { AppStore(this) }
    internal val crashReports by lazy { CrashReports(this) }
    private val http by lazy { SourceHttp() }
    private val defaultAsmrOne by lazy { AsmrOneClient(http) }
    private val asmr18 by lazy { Asmr18Client(http) }

    override fun onCreate() {
        super.onCreate()
        crashReports.install()
    }

    /** Each request captures an immutable mirror configuration, while sharing transport/rate limits. */
    fun source(id: SourceId): SourceClient = when (id) {
        SourceId.ASMR_ONE -> store.data.value.asmrOneApiBaseUrl.takeIf(String::isNotBlank)
            ?.let { AsmrOneClient(http, mirrors = listOf(it)) } ?: defaultAsmrOne
        SourceId.ASMR18 -> asmr18
    }
}
