package com.radiosport.ninegradio.content

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Publish only explicitly saved images. Automatic decoder history stays private. */
internal object DecodedImageGallery {
    fun needsPermission(context:Context)=Build.VERSION.SDK_INT<29 &&
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED
    fun publish(context:Context,file:File):Uri {
        require(file.extension.equals("png",true) && file.isFile)
        if(Build.VERSION.SDK_INT<29) {
            check(!needsPermission(context)) { "需要存储权限才能保存到相册" }
            val directory=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),"9GRadio HF+")
            check(directory.isDirectory || directory.mkdirs()) { "无法创建相册目录" }
            val target=File(directory,file.name);file.copyTo(target,overwrite=false)
            MediaScannerConnection.scanFile(context,arrayOf(target.path),arrayOf("image/png"),null)
            return Uri.fromFile(target)
        }
        val resolver=context.contentResolver
        val values=ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,file.name)
            put(MediaStore.Images.Media.MIME_TYPE,"image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH,"${Environment.DIRECTORY_PICTURES}/9GRadio HF+")
            put(MediaStore.Images.Media.IS_PENDING,1)
        }
        val uri=resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values)
            ?: throw IOException("无法创建相册图片")
        try {
            val stream=resolver.openOutputStream(uri) ?: throw IOException("无法写入相册图片")
            stream.use { output -> file.inputStream().use { it.copyTo(output) } }
            check(resolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null)==1) { "无法完成相册保存" }
            return uri
        } catch(error:Exception) {
            runCatching { resolver.delete(uri,null,null) };throw error
        }
    }
}
