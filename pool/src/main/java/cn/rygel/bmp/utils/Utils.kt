package cn.rygel.bmp.utils

import android.graphics.Bitmap

fun getBitmapByteSize(bmp: Bitmap) = bmp.byteCount * 8

fun getBitmapByteSize(width: Int, height: Int, config: Bitmap.Config?): Int {
    return width * height * getBytesPerPixel(config ?: Bitmap.Config.ARGB_8888)
}

private fun getBytesPerPixel(config: Bitmap.Config): Int {
    // A bitmap by decoding a GIF has null "config" in certain environments.
    return when (config) {
        Bitmap.Config.ALPHA_8 -> 1
        Bitmap.Config.RGB_565, Bitmap.Config.ARGB_4444 -> 2
        Bitmap.Config.ARGB_8888 -> 4
        else -> 4
    }
}