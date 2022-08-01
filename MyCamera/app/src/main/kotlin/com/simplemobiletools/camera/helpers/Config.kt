package com.simplemobiletools.camera.helpers

import android.content.Context
import android.os.Environment
import androidx.camera.core.CameraSelector
import com.simplemobiletools.commons.helpers.BaseConfig
import java.io.File

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    var savePhotosFolder: String
        get(): String {
            var path = prefs.getString(SAVE_PHOTOS, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).toString())
            if (!File(path).exists() || !File(path).isDirectory) {
                path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).toString()
                savePhotosFolder = path
            }
            return path!!
        }
        set(path) = prefs.edit().putString(SAVE_PHOTOS, path).apply()

    var flipPhotos: Boolean
        get() = prefs.getBoolean(FLIP_PHOTOS, true)
        set(flipPhotos) = prefs.edit().putBoolean(FLIP_PHOTOS, flipPhotos).apply()

    var lastUsedCamera: String
        get() = prefs.getString(LAST_USED_CAMERA, "0")!!
        set(cameraId) = prefs.edit().putString(LAST_USED_CAMERA, cameraId).apply()

    var lastUsedCameraLens: Int
        get() = prefs.getInt(LAST_USED_CAMERA_LENS, CameraSelector.LENS_FACING_BACK)
        set(lens) = prefs.edit().putInt(LAST_USED_CAMERA_LENS, lens).apply()

    var initPhotoMode: Boolean
        get() = prefs.getBoolean(INIT_PHOTO_MODE, true)
        set(initPhotoMode) = prefs.edit().putBoolean(INIT_PHOTO_MODE, initPhotoMode).apply()

    var flashlightState: Int
        get() = prefs.getInt(FLASHLIGHT_STATE, FLASH_OFF)
        set(state) = prefs.edit().putInt(FLASHLIGHT_STATE, state).apply()

    var backPhotoResIndex: Int
        get() = prefs.getInt(BACK_PHOTO_RESOLUTION_INDEX, 0)
        set(backPhotoResIndex) = prefs.edit().putInt(BACK_PHOTO_RESOLUTION_INDEX, backPhotoResIndex).apply()

    var backVideoResIndex: Int
        get() = prefs.getInt(BACK_VIDEO_RESOLUTION_INDEX, 0)
        set(backVideoResIndex) = prefs.edit().putInt(BACK_VIDEO_RESOLUTION_INDEX, backVideoResIndex).apply()

    var frontPhotoResIndex: Int
        get() = prefs.getInt(FRONT_PHOTO_RESOLUTION_INDEX, 0)
        set(frontPhotoResIndex) = prefs.edit().putInt(FRONT_PHOTO_RESOLUTION_INDEX, frontPhotoResIndex).apply()

    var frontVideoResIndex: Int
        get() = prefs.getInt(FRONT_VIDEO_RESOLUTION_INDEX, 0)
        set(frontVideoResIndex) = prefs.edit().putInt(FRONT_VIDEO_RESOLUTION_INDEX, frontVideoResIndex).apply()

    var savePhotoMetadata: Boolean
        get() = prefs.getBoolean(SAVE_PHOTO_METADATA, true)
        set(savePhotoMetadata) = prefs.edit().putBoolean(SAVE_PHOTO_METADATA, savePhotoMetadata).apply()

    var photoQuality: Int
        get() = prefs.getInt(PHOTO_QUALITY, 80)
        set(photoQuality) = prefs.edit().putInt(PHOTO_QUALITY, photoQuality).apply()
}
