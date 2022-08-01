package com.simplemobiletools.camera.implementations

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.util.Size
import android.view.*
import android.view.GestureDetector.SimpleOnGestureListener
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.bumptech.glide.load.ImageHeaderParser.UNKNOWN_ORIENTATION
import com.simplemobiletools.camera.R
import com.simplemobiletools.camera.extensions.*
import com.simplemobiletools.camera.helpers.*
import com.simplemobiletools.camera.interfaces.MyPreview
import com.simplemobiletools.camera.models.*
import com.simplemobiletools.commons.extensions.showErrorToast
import com.simplemobiletools.commons.extensions.toast
import com.simplemobiletools.commons.helpers.ensureBackgroundThread

class CameraXPreview(
    private val activity: AppCompatActivity,
    private val previewView: PreviewView,
    private val mediaOutputHelper: MediaOutputHelper,
    private val listener: CameraXPreviewListener,
    initInPhotoMode: Boolean,
) : MyPreview, DefaultLifecycleObserver {

    companion object {
        private const val AF_SIZE = 1.0f / 6.0f
        private const val AE_SIZE = AF_SIZE * 1.5f
    }

    private val config = activity.config
    private val contentResolver = activity.contentResolver
    private val mainExecutor = ContextCompat.getMainExecutor(activity)
    private val displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val videoQualityManager = VideoQualityManager(activity)
    private val imageQualityManager = ImageQualityManager(activity)


    private val orientationEventListener = object : OrientationEventListener(activity, SensorManager.SENSOR_DELAY_NORMAL) {
        @SuppressLint("RestrictedApi")
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == UNKNOWN_ORIENTATION) {
                return
            }

            val rotation = when (orientation) {
                in 45 until 135 -> Surface.ROTATION_270
                in 135 until 225 -> Surface.ROTATION_180
                in 225 until 315 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }

            if (lastRotation != rotation) {
                preview?.targetRotation = rotation
                imageCapture?.targetRotation = rotation
                videoCapture?.targetRotation = rotation
                lastRotation = rotation
            }
        }
    }

    private var preview: Preview? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var camera: Camera? = null
    private var currentRecording: Recording? = null
    private var recordingState: VideoRecordEvent? = null
    private var cameraSelector = config.lastUsedCameraLens.toCameraSelector()
    private var flashMode = FLASH_MODE_OFF
    private var isPhotoCapture = initInPhotoMode
    private var lastRotation = 0

    init {
        bindToLifeCycle()

        previewView.doOnLayout {
            startCamera()
        }
    }

    private fun bindToLifeCycle() {
        activity.lifecycle.addObserver(this)
    }

    private fun startCamera(switching: Boolean = false) {
        imageQualityManager.initSupportedQualities()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                videoQualityManager.initSupportedQualities(provider)
                bindCameraUseCases()
                setupCameraObservers()
            } catch (e: Exception) {
                val errorMessage = if (switching) R.string.camera_switch_error else R.string.camera_open_error
                activity.toast(errorMessage)
            }
        }, mainExecutor)
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val rotation = previewView.display.rotation
        val resolution = if (isPhotoCapture) {
            imageQualityManager.getUserSelectedResolution(cameraSelector)
        } else {
            val selectedQuality = videoQualityManager.getUserSelectedQuality(cameraSelector)
            MySize(selectedQuality.width, selectedQuality.height)
        }

        val rotatedResolution = getRotatedResolution(resolution, rotation)

        preview = buildPreview(rotatedResolution, rotation)
        val captureUseCase = getCaptureUseCase(rotatedResolution, rotation)
        cameraProvider.unbindAll()
        camera = cameraProvider.bindToLifecycle(
            activity,
            cameraSelector,
            preview,
            captureUseCase,
        )

        preview?.setSurfaceProvider(previewView.surfaceProvider)
        setupZoomAndFocus()
    }

    private fun setupCameraObservers() {
        listener.setFlashAvailable(camera?.cameraInfo?.hasFlashUnit() ?: false)
        listener.onChangeCamera(isFrontCameraInUse())

        camera?.cameraInfo?.cameraState?.observe(activity) { cameraState ->
            when (cameraState.type) {
                CameraState.Type.OPEN, CameraState.Type.OPENING -> {
                    listener.setHasFrontAndBackCamera(hasFrontCamera() && hasBackCamera())
                    listener.setCameraAvailable(true)
                }
                CameraState.Type.PENDING_OPEN, CameraState.Type.CLOSING, CameraState.Type.CLOSED -> {
                    listener.setCameraAvailable(false)
                }
            }


        }
    }

    private fun getCaptureUseCase(resolution: Size, rotation: Int): UseCase {
        return if (isPhotoCapture) {
            cameraProvider?.unbind(videoCapture)
            buildImageCapture(resolution, rotation).also {
                imageCapture = it
            }
        } else {
            cameraProvider?.unbind(imageCapture)
            buildVideoCapture().also {
                videoCapture = it
            }
        }
    }

    private fun buildImageCapture(resolution: Size, rotation: Int): ImageCapture {
        return Builder().setCaptureMode(CAPTURE_MODE_MAXIMIZE_QUALITY).setFlashMode(flashMode).setJpegQuality(config.photoQuality).setTargetRotation(rotation)
            .setTargetResolution(resolution).build()
    }

    private fun getRotatedResolution(resolution: MySize, rotationDegrees: Int): Size {
        return if (rotationDegrees == Surface.ROTATION_0 || rotationDegrees == Surface.ROTATION_180) {
            Size(resolution.height, resolution.width)
        } else {
            Size(resolution.width, resolution.height)
        }
    }

    private fun buildPreview(resolution: Size, rotation: Int): Preview {
        return Preview.Builder().setTargetRotation(rotation).setTargetResolution(resolution).build()
    }

    private fun buildVideoCapture(): VideoCapture<Recorder> {
        val qualitySelector = QualitySelector.from(
            videoQualityManager.getUserSelectedQuality(cameraSelector).toCameraXQuality(),
            FallbackStrategy.higherQualityOrLowerThan(Quality.SD),
        )
        val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
        return VideoCapture.withOutput(recorder)
    }

    private fun hasBackCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
    }

    private fun hasFrontCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }

    private fun isFrontCameraInUse(): Boolean {
        return cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupZoomAndFocus() {
        val scaleGesture = camera?.let { ScaleGestureDetector(activity, PinchToZoomOnScaleGestureListener(it.cameraInfo, it.cameraControl)) }
        val gestureDetector = GestureDetector(activity, object : SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                return camera?.cameraInfo?.let {
                    val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                    val width = previewView.width.toFloat()
                    val height = previewView.height.toFloat()
                    val factory = DisplayOrientedMeteringPointFactory(display, it, width, height)
                    val xPos = event.x
                    val yPos = event.y
                    val autoFocusPoint = factory.createPoint(xPos, yPos, AF_SIZE)
                    val autoExposurePoint = factory.createPoint(xPos, yPos, AE_SIZE)
                    val focusMeteringAction =
                        FocusMeteringAction.Builder(autoFocusPoint, FocusMeteringAction.FLAG_AF).addPoint(autoExposurePoint, FocusMeteringAction.FLAG_AE)
                            .disableAutoCancel().build()
                    camera?.cameraControl?.startFocusAndMetering(focusMeteringAction)
                    listener.onFocusCamera(xPos, yPos)
                    true
                } ?: false
            }
        })
        previewView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            scaleGesture?.onTouchEvent(event)
            true
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        orientationEventListener.enable()
    }

    override fun onStop(owner: LifecycleOwner) {
        orientationEventListener.disable()
    }


    override fun toggleFrontBackCamera() {
        val newCameraSelector = if (isFrontCameraInUse()) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
        cameraSelector = newCameraSelector
        config.lastUsedCameraLens = newCameraSelector.toLensFacing()
        startCamera(switching = true)
    }

    override fun toggleFlashlight() {
        val newFlashMode = if (isPhotoCapture) {
            when (flashMode) {
                FLASH_MODE_OFF -> FLASH_MODE_ON
                FLASH_MODE_ON -> FLASH_MODE_AUTO
                FLASH_MODE_AUTO -> FLASH_MODE_OFF
                else -> throw IllegalArgumentException("Unknown mode: $flashMode")
            }
        } else {
            when (flashMode) {
                FLASH_MODE_OFF -> FLASH_MODE_ON
                FLASH_MODE_ON -> FLASH_MODE_OFF
                else -> throw IllegalArgumentException("Unknown mode: $flashMode")
            }.also {
                camera?.cameraControl?.enableTorch(it == FLASH_MODE_ON)
            }
        }
        flashMode = newFlashMode
        imageCapture?.flashMode = newFlashMode
        val appFlashMode = flashMode.toAppFlashMode()
        config.flashlightState = appFlashMode
        listener.onChangeFlashMode(appFlashMode)
    }

    override fun tryTakePicture() {
        val imageCapture = imageCapture ?: throw IllegalStateException("Camera initialization failed.")

        val metadata = Metadata().apply {
            isReversedHorizontal = isFrontCameraInUse() && config.flipPhotos
        }

        val mediaOutput = mediaOutputHelper.getImageMediaOutput()

        if (mediaOutput is MediaOutput.BitmapOutput) {
            imageCapture.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    listener.toggleBottomButtons(false)
                    val bitmap = makeBitmap(image.toJpegByteArray())
                    if (bitmap != null) {
                        listener.onImageCaptured(bitmap)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                }
            })
        } else {
            val outputOptionsBuilder = when (mediaOutput) {
                is MediaOutput.MediaStoreOutput -> OutputFileOptions.Builder(contentResolver, mediaOutput.contentUri, mediaOutput.contentValues)
                is MediaOutput.OutputStreamMediaOutput -> OutputFileOptions.Builder(mediaOutput.outputStream)
                is MediaOutput.BitmapOutput -> throw IllegalStateException("Cannot produce an OutputFileOptions for a bitmap output")
                else -> throw IllegalArgumentException("Unexpected option for image ")
            }

            val outputOptions = outputOptionsBuilder.setMetadata(metadata).build()

            imageCapture.takePicture(outputOptions, mainExecutor, object : OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: OutputFileResults) {
                    ensureBackgroundThread {
                        val savedUri = mediaOutput.uri ?: outputFileResults.savedUri!!


                        activity.runOnUiThread {
                            listener.toggleBottomButtons(false)
                            listener.onMediaSaved(savedUri)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                }
            })
        }

    }

    override fun initPhotoMode() {
        isPhotoCapture = true
        startCamera()
    }

    override fun initVideoMode() {
        isPhotoCapture = false
        startCamera()
    }

    override fun toggleRecording() {
        if (currentRecording == null || recordingState is VideoRecordEvent.Finalize) {
            startRecording()
        } else {
            currentRecording?.stop()
            currentRecording = null
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun startRecording() {
        val videoCapture = videoCapture ?: throw IllegalStateException("Camera initialization failed.")

        val mediaOutput = mediaOutputHelper.getVideoMediaOutput()
        val recording = when (mediaOutput) {
            is MediaOutput.FileDescriptorMediaOutput -> {
                FileDescriptorOutputOptions.Builder(mediaOutput.fileDescriptor).build().let { videoCapture.output.prepareRecording(activity, it) }
            }
            is MediaOutput.FileMediaOutput -> {
                FileOutputOptions.Builder(mediaOutput.file).build().let { videoCapture.output.prepareRecording(activity, it) }
            }
            is MediaOutput.MediaStoreOutput -> {
                MediaStoreOutputOptions.Builder(contentResolver, mediaOutput.contentUri).setContentValues(mediaOutput.contentValues).build()
                    .let { videoCapture.output.prepareRecording(activity, it) }
            }
            else -> throw IllegalArgumentException("Unexpected output option for video $mediaOutput")
        }

        currentRecording = recording.withAudioEnabled().start(mainExecutor) { recordEvent ->
            recordingState = recordEvent
            when (recordEvent) {
                is VideoRecordEvent.Start -> {

                    listener.onVideoRecordingStarted()
                }

                is VideoRecordEvent.Status -> {
                    listener.onVideoDurationChanged(recordEvent.recordingStats.recordedDurationNanos)
                }

                is VideoRecordEvent.Finalize -> {

                    listener.onVideoRecordingStopped()
                    if (!recordEvent.hasError()) {
                        listener.onMediaSaved(mediaOutput.uri ?: recordEvent.outputResults.outputUri)
                    }
                }
            }
        }
    }

}

fun VideoQuality.toCameraXQuality(): Quality {
    return when (this) {
        VideoQuality.UHD -> Quality.UHD
        VideoQuality.FHD -> Quality.FHD
        VideoQuality.HD -> Quality.HD
        VideoQuality.SD -> Quality.SD
    }
}

fun Int.toAppFlashMode(): Int {
    return when (this) {
        FLASH_MODE_ON -> FLASH_ON
        FLASH_MODE_OFF -> FLASH_OFF
        FLASH_MODE_AUTO -> FLASH_AUTO
        else -> throw java.lang.IllegalArgumentException("Unknown mode: $this")
    }
}

fun Int.toCameraSelector(): CameraSelector {
    return if (this == CameraSelector.LENS_FACING_FRONT) {
        CameraSelector.DEFAULT_FRONT_CAMERA
    } else {
        CameraSelector.DEFAULT_BACK_CAMERA
    }
}

fun CameraSelector.toLensFacing(): Int {
    return if (this == CameraSelector.DEFAULT_FRONT_CAMERA) {
        CameraSelector.LENS_FACING_FRONT
    } else {
        CameraSelector.LENS_FACING_BACK
    }
}

fun ImageProxy.toJpegByteArray(): ByteArray {
    val buffer = planes.first().buffer
    val jpegImageData = ByteArray(buffer.remaining())
    buffer[jpegImageData]
    return jpegImageData
}

fun makeBitmap(jpegData: ByteArray): Bitmap? {
    return try {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true

        BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size, options)

        if (options.mCancel || options.outWidth == -1 || options.outHeight == -1) {
            return null
        }
        options.inJustDecodeBounds = false
        options.inDither = false
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        BitmapFactory.decodeByteArray(
            jpegData, 0, jpegData.size, options
        )
    } catch (ex: OutOfMemoryError) {
        null
    }
}

class ImageQualityManager(
    private val activity: AppCompatActivity,
) {

    companion object {
        private val CAMERA_LENS = arrayOf(CameraCharacteristics.LENS_FACING_FRONT, CameraCharacteristics.LENS_FACING_BACK)
    }

    private val cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val config = activity.config
    private val imageQualities = mutableListOf<CameraSelectorImageQualities>()

    fun initSupportedQualities() {
        if (imageQualities.isEmpty()) {
            for (cameraId in cameraManager.cameraIdList) {
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    for (lens in CAMERA_LENS) {
                        if (characteristics.get(CameraCharacteristics.LENS_FACING) == lens) {
                            val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                            val imageSizes = configMap.getOutputSizes(ImageFormat.JPEG).map { MySize(it.width, it.height) }
                            val cameraSelector = lens.toCameraSelector()
                            imageQualities.add(CameraSelectorImageQualities(cameraSelector, imageSizes))
                        }
                    }
                } catch (e: Exception) {
                    activity.showErrorToast(e)
                }
            }
        }
    }

    private fun Int.toCameraSelector(): CameraSelector {
        return if (this == CameraCharacteristics.LENS_FACING_FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    fun getUserSelectedResolution(cameraSelector: CameraSelector): MySize {
        val index = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) config.frontPhotoResIndex else config.backPhotoResIndex
        return imageQualities.filter { it.camSelector == cameraSelector }.flatMap { it.qualities }
            .sortedWith(compareByDescending<MySize> { it.ratio }.thenByDescending { it.pixels }).distinctBy { it.pixels }
            .filter { it.megaPixels != "0.0" }[index]
    }


}

class VideoQualityManager(
    private val activity: AppCompatActivity,
) {

    companion object {
        private val QUALITIES = listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
        private val CAMERA_SELECTORS = arrayOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
    }

    private val config = activity.config
    private val videoQualities = mutableListOf<CameraSelectorVideoQualities>()

    fun initSupportedQualities(cameraProvider: ProcessCameraProvider) {
        if (videoQualities.isEmpty()) {
            for (camSelector in CAMERA_SELECTORS) {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(activity, camSelector)
                try {
                    if (cameraProvider.hasCamera(camSelector)) {
                        QualitySelector.getSupportedQualities(camera.cameraInfo).filter(QUALITIES::contains).also { allQualities ->
                            val qualities = allQualities.map { it.toVideoQuality() }
                            videoQualities.add(CameraSelectorVideoQualities(camSelector, qualities))
                        }
                    }
                } catch (e: Exception) {
                    activity.showErrorToast(e)
                }
            }
        }
    }

    fun getUserSelectedQuality(cameraSelector: CameraSelector): VideoQuality {
        var selectionIndex = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) config.frontVideoResIndex else config.backVideoResIndex
        selectionIndex = selectionIndex.coerceAtLeast(0)
        return getSupportedQualities(cameraSelector).getOrElse(selectionIndex) { VideoQuality.HD }
    }

    private fun getSupportedQualities(cameraSelector: CameraSelector): List<VideoQuality> {
        return videoQualities.filter { it.camSelector == cameraSelector }.flatMap { it.qualities }.sortedByDescending { it.pixels }
    }
}

fun Quality.toVideoQuality(): VideoQuality {
    return when (this) {
        Quality.UHD -> VideoQuality.UHD
        Quality.FHD -> VideoQuality.FHD
        Quality.HD -> VideoQuality.HD
        Quality.SD -> VideoQuality.SD
        else -> throw IllegalArgumentException("Unsupported quality: $this")
    }
}

class PinchToZoomOnScaleGestureListener(
    private val cameraInfo: CameraInfo,
    private val cameraControl: CameraControl,
) : ScaleGestureDetector.SimpleOnScaleGestureListener() {

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        val zoomState = cameraInfo.zoomState.value ?: return false
        val zoomRatio = calculateZoomRatio(zoomState, detector.scaleFactor)
        cameraControl.setZoomRatio(zoomRatio)
        return true
    }

    private fun calculateZoomRatio(zoomState: ZoomState, pinchToZoomScale: Float): Float {
        val clampedRatio = zoomState.zoomRatio * speedUpZoomBy2X(pinchToZoomScale)
        // Clamp the ratio with the zoom range.
        return clampedRatio.coerceAtLeast(zoomState.minZoomRatio).coerceAtMost(zoomState.maxZoomRatio)
    }

    private fun speedUpZoomBy2X(scaleFactor: Float): Float {
        return if (scaleFactor > 1f) {
            1.0f + (scaleFactor - 1.0f) * 2
        } else {
            1.0f - (1.0f - scaleFactor) * 2
        }
    }
}

data class CameraSelectorImageQualities(
    val camSelector: CameraSelector,
    val qualities: List<MySize>,
)

data class CameraSelectorVideoQualities(
    val camSelector: CameraSelector,
    val qualities: List<VideoQuality>,
)

enum class VideoQuality(val width: Int, val height: Int) {
    UHD(3840, 2160), FHD(1920, 1080), HD(1280, 720), SD(720, 480);

    val pixels: Int = width * height
}
