package com.simplemobiletools.camera.models

import android.util.Size

data class MySize(val width: Int, val height: Int) {
    companion object {
        private const val ONE_MEGA_PIXEL = 1000000
    }

    val ratio = width / height.toFloat()

    val pixels: Int = width * height

    val megaPixels: String =  String.format("%.1f", (width * height.toFloat()) / ONE_MEGA_PIXEL)

    fun toSize() = Size(width, height)
}
