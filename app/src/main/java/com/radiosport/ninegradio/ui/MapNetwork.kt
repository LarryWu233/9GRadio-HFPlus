package com.radiosport.ninegradio.ui

import com.radiosport.ninegradio.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.IOException

/** MapLibre retains its native HTTP cache; no tile prefetch or bulk-download path is added. */
object MapNetwork {
    data class TileStatus(val available: Boolean?=null,val code: Int?=null)
    private val _status=MutableStateFlow(TileStatus())
    val status=_status.asStateFlow()
    private val client by lazy {
        OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request().newBuilder().header("User-Agent","9GRadioHFPlus/${BuildConfig.VERSION_NAME}").build()
            val original=request.url.host=="tile.openstreetmap.org"
            try {
                chain.proceed(request).also { response ->
                    if(original) _status.value=TileStatus(response.isSuccessful || response.code==304,response.code)
                }
            } catch(error: IOException) {
                if(original && !chain.call().isCanceled()) _status.value=TileStatus(false)
                throw error
            }
        }.build()
    }
    fun install() { _status.value=TileStatus(); HttpRequestUtil.setOkHttpClient(client) }
    fun resetStatus() { _status.value=TileStatus() }
}
