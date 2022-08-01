package com.simplemobiletools.camera.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.*
import android.widget.RelativeLayout
import androidx.camera.view.PreviewView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.simplemobiletools.camera.BuildConfig
import com.simplemobiletools.camera.R
import com.simplemobiletools.camera.extensions.config
import com.simplemobiletools.camera.helpers.*
import com.simplemobiletools.camera.implementations.CameraXPreview
import com.simplemobiletools.camera.implementations.CameraXPreviewListener
import com.simplemobiletools.camera.interfaces.MyPreview
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import kotlinx.android.synthetic.main.activity_main.*
import java.util.concurrent.TimeUnit

class MainActivity : SimpleActivity(), PhotoProcessor.MediaSavedListener, CameraXPreviewListener {
    companion object {
        private const val CAPTURE_ANIMATION_DURATION = 100L
    }

    lateinit var mTimerHandler: Handler
    private lateinit var mOrientationEventListener: OrientationEventListener
    private lateinit var mFocusCircleView: FocusCircleView
    private lateinit var mFadeHandler: Handler
    private lateinit var mCameraImpl: MyCameraImpl

    private var mPreview: MyPreview? = null
    private var mPreviewUri: Uri? = null
    private var mIsInPhotoMode = true
    private var mIsCameraAvailable = false
    private var mIsHardwareShutterHandled = false
    private var mCurrVideoRecTimer = 0
    var mLastHandledOrientation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        useDynamicTheme = false
        super.onCreate(savedInstanceState)
        appLaunched(BuildConfig.APPLICATION_ID)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        initVariables()
        tryInitCamera()
        supportActionBar?.hide()
        setupOrientationEventListener()
    }

    override fun onResume() {
        super.onResume()
        if (hasStorageAndCameraPermissions()) {
            resumeCameraItems()
            setupPreviewImage(mIsInPhotoMode)
            mFocusCircleView.setStrokeColor(getProperPrimaryColor())

            if (isVideoCaptureIntent() && mIsInPhotoMode) {
                handleTogglePhotoVideo()
                checkButtons()
            }
            toggleBottomButtons(false)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (hasStorageAndCameraPermissions()) {
            mOrientationEventListener.enable()
        }
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!hasStorageAndCameraPermissions() || isAskingPermissions) {
            return
        }

        mFadeHandler.removeCallbacksAndMessages(null)

        hideTimer()
        mOrientationEventListener.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        mPreview = null
    }

    private fun initVariables() {
        mIsInPhotoMode = if (isVideoCaptureIntent()) {
            false
        } else if (isImageCaptureIntent()) {
            true
        } else {
            config.initPhotoMode
        }
        mIsCameraAvailable = false
        mIsHardwareShutterHandled = false
        mCurrVideoRecTimer = 0
        mLastHandledOrientation = 0
        mCameraImpl = MyCameraImpl()
        config.lastUsedCamera = mCameraImpl.getBackCameraId().toString()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_CAMERA && !mIsHardwareShutterHandled) {
            mIsHardwareShutterHandled = true
            shutterPressed()
            true
        } else if (!mIsHardwareShutterHandled && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            mIsHardwareShutterHandled = true
            shutterPressed()
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_CAMERA || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            mIsHardwareShutterHandled = false
        }
        return super.onKeyUp(keyCode, event)
    }


    private fun tryInitCamera() {
        handlePermission(PERMISSION_CAMERA) { grantedCameraPermission ->
            if (grantedCameraPermission) {
                handlePermission(PERMISSION_WRITE_STORAGE) { grantedStoragePermission ->
                    if (grantedStoragePermission) {
                        if (mIsInPhotoMode) {
                            initializeCamera()
                        } else {
                            handlePermission(PERMISSION_RECORD_AUDIO) { grantedRecordAudioPermission ->
                                if (grantedRecordAudioPermission) {
                                    initializeCamera()
                                } else {
                                    toast(R.string.no_audio_permissions)
                                    togglePhotoVideoMode()
                                    tryInitCamera()
                                }
                            }
                        }
                    } else {
                        toast(R.string.no_storage_permissions)
                        finish()
                    }
                }
            } else {
                toast(R.string.no_camera_permissions)
                finish()
            }
        }
    }

    private fun isImageCaptureIntent(): Boolean = intent?.action == MediaStore.ACTION_IMAGE_CAPTURE || intent?.action == MediaStore.ACTION_IMAGE_CAPTURE_SECURE

    private fun isVideoCaptureIntent(): Boolean = intent?.action == MediaStore.ACTION_VIDEO_CAPTURE

    private fun checkImageCaptureIntent() {
        if (isImageCaptureIntent()) {

            val output = intent.extras?.get(MediaStore.EXTRA_OUTPUT)
            if (output != null && output is Uri) {
                mPreview?.setTargetUri(output)
            }
        }
    }

    private fun checkVideoCaptureIntent() {
        if (intent?.action == MediaStore.ACTION_VIDEO_CAPTURE) {
            mIsInPhotoMode = false

            shutter.setImageResource(R.drawable.ic_video_rec)
        }
    }

    private fun initializeCamera() {
        setContentView(R.layout.activity_main)
        initButtons()

        (btn_holder.layoutParams as RelativeLayout.LayoutParams).setMargins(
            0, 0, 0, (navigationBarHeight + resources.getDimension(R.dimen.activity_margin)).toInt()
        )

        checkVideoCaptureIntent()
        val outputUri = intent.extras?.get(MediaStore.EXTRA_OUTPUT) as? Uri
        val is3rdPartyIntent = isVideoCaptureIntent() || isImageCaptureIntent()
        mPreview = CameraXInitializer(this).createCameraXPreview(
            preview_view,
            listener = this,
            outputUri = outputUri,
            is3rdPartyIntent = is3rdPartyIntent,
            initInPhotoMode = mIsInPhotoMode,
        )
        checkImageCaptureIntent()
        mPreview?.setIsImageCaptureIntent(isImageCaptureIntent())

        val imageDrawable =
            if (config.lastUsedCamera == mCameraImpl.getBackCameraId().toString()) R.drawable.ic_camera_front_vector else R.drawable.ic_camera_rear_vector
        toggle_camera.setImageResource(imageDrawable)

        mFocusCircleView = FocusCircleView(applicationContext)
        view_holder.addView(mFocusCircleView)

        mTimerHandler = Handler(Looper.getMainLooper())
        mFadeHandler = Handler(Looper.getMainLooper())
        setupPreviewImage(true)

        val initialFlashlightState = FLASH_OFF
        mPreview!!.setFlashlightState(initialFlashlightState)
        updateFlashlightState(initialFlashlightState)
    }

    private fun initButtons() {
        toggle_camera.setOnClickListener { toggleCamera() }
        last_photo_video_preview.setOnClickListener { showLastMediaPreview() }
        toggle_flash.setOnClickListener { toggleFlash() }
        shutter.setOnClickListener { shutterPressed() }
        toggle_photo_video.setOnClickListener { handleTogglePhotoVideo() }

    }

    private fun toggleCamera() {
        if (checkCameraAvailable()) {
            mPreview!!.toggleFrontBackCamera()
        }
    }

    private fun showLastMediaPreview() {
        if (mPreviewUri != null) {
            val path = applicationContext.getRealPathFromURI(mPreviewUri!!) ?: mPreviewUri!!.toString()
            openPathIntent(path, false, BuildConfig.APPLICATION_ID)
        }
    }

    private fun toggleFlash() {
        if (checkCameraAvailable()) {
            mPreview?.toggleFlashlight()
        }
    }

    fun updateFlashlightState(state: Int) {
        config.flashlightState = state
        val flashDrawable = when (state) {
            FLASH_OFF -> R.drawable.ic_flash_off_vector
            FLASH_ON -> R.drawable.ic_flash_on_vector
            else -> R.drawable.ic_flash_auto_vector
        }
        toggle_flash.setImageResource(flashDrawable)
    }

    private fun shutterPressed() {
        if (checkCameraAvailable()) {
            handleShutter()
        }
    }

    private fun handleShutter() {
        if (mIsInPhotoMode) {
            toggleBottomButtons(true)
            mPreview?.tryTakePicture()
            capture_black_screen.animate().alpha(0.8f).setDuration(CAPTURE_ANIMATION_DURATION).withEndAction {
                capture_black_screen.animate().alpha(0f).setDuration(CAPTURE_ANIMATION_DURATION).start()
            }.start()
        } else {
            mPreview?.toggleRecording()
        }
    }


    private fun handleTogglePhotoVideo() {
        handlePermission(PERMISSION_RECORD_AUDIO) {
            if (it) {
                togglePhotoVideo()
            } else {
                toast(R.string.no_audio_permissions)
                if (isVideoCaptureIntent()) {
                    finish()
                }
            }
        }
    }

    private fun togglePhotoVideo() {
        if (!checkCameraAvailable()) {
            return
        }

        if (isVideoCaptureIntent()) {
            mPreview?.initVideoMode()
        }

        mPreview?.setFlashlightState(FLASH_OFF)
        hideTimer()
        togglePhotoVideoMode()
        checkButtons()
        toggleBottomButtons(false)
    }

    private fun togglePhotoVideoMode() {
        mIsInPhotoMode = !mIsInPhotoMode
        config.initPhotoMode = mIsInPhotoMode
    }

    private fun checkButtons() {
        if (mIsInPhotoMode) {
            initPhotoMode()
        } else {
            tryInitVideoMode()
        }
    }

    private fun initPhotoMode() {
        toggle_photo_video.setImageResource(R.drawable.ic_video_vector)
        shutter.setImageResource(R.drawable.ic_shutter_vector)
        mPreview?.initPhotoMode()
        setupPreviewImage(true)
    }

    private fun tryInitVideoMode() {
        try {
            mPreview?.initVideoMode()
            initVideoButtons()
        } catch (e: Exception) {
            if (!isVideoCaptureIntent()) {
                toast(R.string.video_mode_error)
            }
        }
    }

    private fun initVideoButtons() {
        toggle_photo_video.setImageResource(R.drawable.ic_camera_vector)
        shutter.setImageResource(R.drawable.ic_video_rec)
        setupPreviewImage(false)
        mPreview?.checkFlashlight()
    }

    private fun setupPreviewImage(isPhoto: Boolean) {
        val uri = if (isPhoto) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val lastMediaId = getLatestMediaId(uri)
        if (lastMediaId == 0L) {
            return
        }

        mPreviewUri = Uri.withAppendedPath(uri, lastMediaId.toString())


        loadLastTakenMedia(mPreviewUri)
    }

    private fun loadLastTakenMedia(uri: Uri?) {
        mPreviewUri = uri
        runOnUiThread {
            if (!isDestroyed) {
                val options = RequestOptions().centerCrop().diskCacheStrategy(DiskCacheStrategy.NONE)

                Glide.with(this).load(uri).apply(options).transition(DrawableTransitionOptions.withCrossFade()).into(last_photo_video_preview)
            }
        }
    }

    private fun showTimer() {
        video_rec_curr_timer.beVisible()
        setupTimer()
    }

    private fun hideTimer() {
        video_rec_curr_timer.text = 0.getFormattedDuration()
        video_rec_curr_timer.beGone()
        mCurrVideoRecTimer = 0
        mTimerHandler.removeCallbacksAndMessages(null)
    }

    private fun setupTimer() {
        runOnUiThread(object : Runnable {
            override fun run() {
                video_rec_curr_timer.text = mCurrVideoRecTimer++.getFormattedDuration()
                mTimerHandler.postDelayed(this, 1000L)
            }
        })
    }

    private fun resumeCameraItems() {

        if (!mIsInPhotoMode) {
            initVideoButtons()
        }
    }

    private fun hasStorageAndCameraPermissions(): Boolean {
        return if (mIsInPhotoMode) {
            hasPermission(PERMISSION_WRITE_STORAGE) && hasPermission(PERMISSION_CAMERA)
        } else {
            hasPermission(PERMISSION_WRITE_STORAGE) && hasPermission(PERMISSION_CAMERA) && hasPermission(PERMISSION_RECORD_AUDIO)
        }
    }

    private fun setupOrientationEventListener() {
        mOrientationEventListener = object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                if (isDestroyed) {
                    mOrientationEventListener.disable()
                    return
                }

                val currOrient = when (orientation) {
                    in 75..134 -> ORIENT_LANDSCAPE_RIGHT
                    in 225..289 -> ORIENT_LANDSCAPE_LEFT
                    else -> ORIENT_PORTRAIT
                }

                if (currOrient != mLastHandledOrientation) {
                    val degrees = when (currOrient) {
                        ORIENT_LANDSCAPE_LEFT -> 90
                        ORIENT_LANDSCAPE_RIGHT -> -90
                        else -> 0
                    }

                    animateViews(degrees)
                    mLastHandledOrientation = currOrient
                }
            }
        }
    }

    private fun animateViews(degrees: Int) {
        val views = arrayOf<View>(toggle_camera, toggle_flash, toggle_photo_video, shutter, last_photo_video_preview)
        for (view in views) {
            rotate(view, degrees)
        }
    }

    private fun rotate(view: View, degrees: Int) = view.animate().rotation(degrees.toFloat()).start()

    private fun checkCameraAvailable(): Boolean {
        if (!mIsCameraAvailable) {
            toast(R.string.camera_unavailable)
        }
        return mIsCameraAvailable
    }

    override fun setCameraAvailable(available: Boolean) {
        mIsCameraAvailable = available
    }

    override fun setHasFrontAndBackCamera(hasFrontAndBack: Boolean) {
        toggle_camera?.beVisibleIf(hasFrontAndBack)
    }

    override fun setFlashAvailable(available: Boolean) {
        if (available) {
            toggle_flash.beVisible()
        } else {
            toggle_flash.beInvisible()
            toggle_flash.setImageResource(R.drawable.ic_flash_off_vector)
            mPreview?.setFlashlightState(FLASH_OFF)
        }
    }

    override fun onChangeCamera(frontCamera: Boolean) {
        toggle_camera.setImageResource(if (frontCamera) R.drawable.ic_camera_rear_vector else R.drawable.ic_camera_front_vector)
    }

    override fun toggleBottomButtons(hide: Boolean) {
        runOnUiThread {
            val alpha = if (hide) 0f else 1f
            shutter.animate().alpha(alpha).start()
            toggle_camera.animate().alpha(alpha).start()
            toggle_flash.animate().alpha(alpha).start()

            shutter.isClickable = !hide
            toggle_camera.isClickable = !hide
            toggle_flash.isClickable = !hide
        }
    }

    override fun onMediaSaved(uri: Uri) {
        loadLastTakenMedia(uri)
        if (isImageCaptureIntent()) {
            Intent().apply {
                data = uri
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                setResult(Activity.RESULT_OK, this)
            }
            finish()
        } else if (isVideoCaptureIntent()) {
            Intent().apply {
                data = uri
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                setResult(Activity.RESULT_OK, this)
            }
            finish()
        }
    }

    override fun onImageCaptured(bitmap: Bitmap) {
        if (isImageCaptureIntent()) {
            Intent().apply {
                putExtra("data", bitmap)
                setResult(Activity.RESULT_OK, this)
            }
            finish()
        }
    }

    override fun onChangeFlashMode(flashMode: Int) {
        updateFlashlightState(flashMode)
    }

    override fun onVideoRecordingStarted() {
        shutter.setImageResource(R.drawable.ic_video_stop)
        toggle_camera.beInvisible()
        video_rec_curr_timer.beVisible()
    }

    override fun onVideoRecordingStopped() {
        shutter.setImageResource(R.drawable.ic_video_rec)
        video_rec_curr_timer.text = 0.getFormattedDuration()
        video_rec_curr_timer.beGone()
        toggle_camera.beVisible()
    }

    override fun onVideoDurationChanged(durationNanos: Long) {
        val seconds = TimeUnit.NANOSECONDS.toSeconds(durationNanos).toInt()
        video_rec_curr_timer.text = seconds.getFormattedDuration()
    }

    override fun onFocusCamera(xPos: Float, yPos: Float) {
        mFocusCircleView.drawFocusCircle(xPos, yPos)
    }

    fun setRecordingState(isRecording: Boolean) {
        runOnUiThread {
            if (isRecording) {
                shutter.setImageResource(R.drawable.ic_video_stop)
                toggle_camera.beInvisible()
                showTimer()
            } else {
                shutter.setImageResource(R.drawable.ic_video_rec)
                hideTimer()
            }
        }
    }

    fun videoSaved(uri: Uri) {
        setupPreviewImage(false)
        if (isVideoCaptureIntent()) {
            Intent().apply {
                data = uri
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                setResult(Activity.RESULT_OK, this)
            }
            finish()
        }
    }

    override fun mediaSaved(path: String) {
        rescanPaths(arrayListOf(path)) {
            setupPreviewImage(true)
            Intent(BROADCAST_REFRESH_MEDIA).apply {
                putExtra(REFRESH_PATH, path)
                `package` = "com.simplemobiletools.gallery"
                sendBroadcast(this)
            }
        }

        if (isImageCaptureIntent()) {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

}

class CameraXInitializer(private val activity: BaseSimpleActivity) {

    fun createCameraXPreview(
        previewView: PreviewView,
        listener: CameraXPreviewListener,
        outputUri: Uri?,
        is3rdPartyIntent: Boolean,
        initInPhotoMode: Boolean,
    ): CameraXPreview {

        val mediaOutputHelper = newMediaOutputHelper(outputUri, is3rdPartyIntent)
        return CameraXPreview(
            activity,
            previewView,
            mediaOutputHelper,
            listener,
            initInPhotoMode,
        )
    }

    private fun newMediaOutputHelper(

        outputUri: Uri?,
        is3rdPartyIntent: Boolean,
    ): MediaOutputHelper {
        return MediaOutputHelper(
            activity,
            outputUri,
            is3rdPartyIntent,
        )
    }

}

class MyCameraImpl() {
    fun getBackCameraId() = CameraCharacteristics.LENS_FACING_BACK
}

class FocusCircleView(context: Context) : ViewGroup(context) {
    private val CIRCLE_RADIUS = 50f
    private val CIRCLE_DURATION = 500L

    private var mDrawCircle = false
    private var mHandler: Handler
    private var mPaint: Paint
    private var mLastCenterX = 0f
    private var mLastCenterY = 0f

    init {
        setWillNotDraw(false)
        mHandler = Handler()
        mPaint = Paint().apply {
            style = Paint.Style.STROKE
            color = context.getProperPrimaryColor()
            strokeWidth = 2f
        }
    }

    fun setStrokeColor(color: Int) {
        mPaint.color = color
    }

    fun drawFocusCircle(x: Float, y: Float) {
        mLastCenterX = x
        mLastCenterY = y
        toggleCircle(true)

        mHandler.removeCallbacksAndMessages(null)
        mHandler.postDelayed({
            toggleCircle(false)
        }, CIRCLE_DURATION)
    }

    private fun toggleCircle(show: Boolean) {
        mDrawCircle = show
        invalidate()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {}

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mDrawCircle) {
            canvas.drawCircle(mLastCenterX, mLastCenterY, CIRCLE_RADIUS, mPaint)
        }
    }
}
