package com.radiosport.ninegradio.content

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class EibiRepository(private val context: Context) {
    companion object { private val cacheLock=Any() }
    data class Data(val entries: List<EibiEntry>, val season: String, val origin: String)
    private val directory=File(context.filesDir,"eibi").apply { mkdirs() }
    private val cache=File(directory,"schedule.csv")
    private val meta=File(directory,"source.txt")
    private val snapshot=android.util.AtomicFile(File(directory,"schedule-cache.bin"))
    fun load(): Data = synchronized(cacheLock) {
        runCatching {
            java.io.DataInputStream(snapshot.openRead()).use { input ->
                val season=input.readUTF(); val origin=input.readUTF(); val count=input.readInt()
                require(count in 1..2_000_000)
                val bytes=ByteArray(count); input.readFully(bytes); parse(bytes,season,origin).also { require(it.entries.isNotEmpty()) }
            }
        }.getOrNull()?.let { return@synchronized it }
        if(cache.exists() && meta.exists()) return parse(cache.readBytes(),meta.readLines().getOrElse(0){"导入"},meta.readLines().drop(1).joinToString(" "))
        parse(context.assets.open("eibi/sked-b26.csv").use { it.readBytes() },"B26","EiBi · 2026-10-31")
    }
    fun import(bytes: ByteArray,name: String): Data {
        val season=Regex("[abAB]\\d{2}").find(name)?.value?.uppercase() ?: "未知季节"
        return persist(bytes,season,"本地导入 · ${java.time.LocalDate.now(java.time.ZoneOffset.UTC)}")
    }
    fun update(): Data {
        val client=OkHttpClient.Builder().connectTimeout(4,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS).callTimeout(8,TimeUnit.SECONDS)
            .dns(object: okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> =
                    okhttp3.Dns.SYSTEM.lookup(hostname).sortedBy { if(it is java.net.Inet4Address) 0 else 1 }
            }).build()
        val httpsClient=client.newBuilder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(35,TimeUnit.SECONDS).build()
        fun get(url: String): ByteArray=(if(url.startsWith("https:")) httpsClient else client).newCall(Request.Builder().url(url)
            .header("User-Agent","9GRadio-HFPlus/0.18 (EiBi schedule)")
            .header("Accept","application/vnd.github.raw+json")
            .header("Connection","close").build()).execute().use {
            check(it.isSuccessful) { "HTTP ${it.code}" }
            val body=it.body ?: error("节目表为空")
            body.byteStream().use { stream ->
                val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
                while(true) { val n=stream.read(buffer); if(n<0) break; out.write(buffer,0,n); check(out.size()<=2_000_000) { "文件过大" } }
                out.toByteArray()
            }
        }
        var failure: Exception?=null
        for(host in listOf("http://www.eibispace.de/","http://eibispace.de/")) try {
            val index=get(host).toString(Charsets.ISO_8859_1)
            val path=Regex("dx/sked-([ab]\\d{2})\\.csv",RegexOption.IGNORE_CASE).find(index) ?: error("未找到官方 CSV")
            return persist(get(host+path.value),path.groupValues[1].uppercase(),"EiBi · ${java.time.LocalDate.now(java.time.ZoneOffset.UTC)}")
        } catch(e: Exception) { failure=e }
        // SkyWave mirrors the unchanged EiBi CSV daily through its public GitHub Actions workflow.
        // This fallback uses normal certificate validation and is labelled separately in the UI.
        val season=EibiSchedule.currentSeason(java.time.LocalDate.now(java.time.ZoneOffset.UTC))
        for(url in listOf("https://raw.githubusercontent.com/cdburgess75/SkyWave/data/sked-${season.lowercase()}.csv",
            "https://api.github.com/repos/cdburgess75/SkyWave/contents/sked-${season.lowercase()}.csv?ref=data")) try {
            return persist(get(url),
                season,"EiBi · SkyWave HTTPS 镜像 · ${java.time.LocalDate.now(java.time.ZoneOffset.UTC)}")
        } catch(e: Exception) { failure=e }
        throw failure ?: java.io.IOException("节目表下载失败")
    }
    private fun persist(bytes: ByteArray,season: String,origin: String): Data = synchronized(cacheLock) {
        require(bytes.size<=2_000_000) { "文件过大" }
        val data=parse(bytes,season,origin); require(data.entries.isNotEmpty()) { "不是有效的 EiBi CSV" }
        val output=snapshot.startWrite()
        try {
            val stream=java.io.DataOutputStream(output)
            stream.writeUTF(season); stream.writeUTF(origin); stream.writeInt(bytes.size); stream.write(bytes); stream.flush()
            snapshot.finishWrite(output)
        } catch(e: Exception) { snapshot.failWrite(output); throw e }
        data
    }
    private fun parse(bytes: ByteArray,season: String,origin: String): Data {
        val utf=bytes.toString(Charsets.UTF_8)
        val text=if('\uFFFD' in utf) bytes.toString(Charsets.ISO_8859_1) else utf
        return Data(EibiSchedule.parse(text.removePrefix("\uFEFF")),season,origin)
    }
}
