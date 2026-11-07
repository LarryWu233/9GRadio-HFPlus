package com.radiosport.ninegradio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radiosport.ninegradio.content.EibiRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EibiRepositoryTest {
    @Test fun localImportAndInvalidImportPreserveCache() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.filesDir,"eibi")
        val names=listOf("schedule.csv","source.txt","schedule-cache.bin","schedule-cache.bin.bak")
        val before=names.associateWith { File(directory,it).takeIf { f -> f.exists() }?.readBytes() }
        try {
            val repository=EibiRepository(context)
            val csv="kHz;Time;Days;ITU;Station;Lng;Target;Remarks;P;Start;Stop;\n11920;2300-0100;6;CHN;Local fixture;M;FE;x;1;;;\n"
            val imported=repository.import(csv.toByteArray(),"sked-b26.csv")
            assertEquals("B26",imported.season); assertEquals("Local fixture",imported.entries.single().station)
            try { repository.import("not a CSV".toByteArray(),"bad.csv"); fail("Invalid import accepted") } catch(expected: IllegalArgumentException) { }
            assertEquals(imported.entries,repository.load().entries)
        } finally {
            names.forEach { name -> val file=File(directory,name); val bytes=before[name]
                if(bytes==null) file.delete() else file.writeBytes(bytes)
            }
        }
    }
    @Test fun refreshUsesAvailablePublicSource() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.filesDir,"eibi")
        val names=listOf("schedule.csv","source.txt","schedule-cache.bin","schedule-cache.bin.bak")
        val before=names.associateWith { File(directory,it).takeIf { f -> f.exists() }?.readBytes() }
        try {
            File(context.cacheDir,"alpha18-eibi-dns.txt").writeText(listOf("www.eibispace.de","eibispace.de").joinToString("\n") { host ->
                "$host: ${okhttp3.Dns.SYSTEM.lookup(host).joinToString { it.hostAddress.orEmpty() }}"
            })
            val repository=EibiRepository(context)
            val downloaded=repository.update()
            assertTrue(downloaded.season.matches(Regex("[AB][0-9]{2}"))); assertTrue(downloaded.entries.size>3000)
            assertEquals(downloaded.entries,repository.load().entries)
            File(context.cacheDir,"alpha18-eibi-refresh-source.txt").writeText("${downloaded.origin}\n${downloaded.season}\n${downloaded.entries.size} entries")
        } finally { names.forEach { name -> val file=File(directory,name); before[name]?.let { file.writeBytes(it) } ?: file.delete() } }
    }
}
