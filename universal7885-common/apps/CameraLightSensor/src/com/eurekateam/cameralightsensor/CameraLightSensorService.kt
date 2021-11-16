package com.eurekateam.cameralightsensor

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraCaptureSession
import kotlin.jvm.Volatile
import android.hardware.camera2.CameraManager
import androidx.core.app.NotificationCompat
import android.hardware.camera2.CameraAccessException
import android.database.ContentObserver
import android.provider.Settings.SettingNotFoundException
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import kotlin.Throws
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraManager.AvailabilityCallback
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.*
import androidx.core.content.ContextCompat
import android.provider.Settings
import android.util.Log
import java.lang.Exception
import java.lang.IllegalStateException

open class CameraLightSensorService : Service() {
    var screenStateFilter: IntentFilter? = null
    private var mContext: Context? = null
    private var mRegistered = false
    var destroy = false
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    @Volatile
    var avail = true
    private var imageReader: ImageReader? = null
    private var manager: CameraManager? = null
    private var mServiceStarted = false

    @Volatile
    private var mLock = false
    private var mThreadRunning = false
    private fun PushNotification(): Notification {
        val nm = mContext!!.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            mContext!!.basePackageName, "CameraLightSensor",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.isBlockable = true
        nm.createNotificationChannel(channel)
        val builder = NotificationCompat.Builder(mContext!!, mContext!!.basePackageName)
        val notificationIntent = Intent(mContext, CameraLightSensorService::class.java)
        val contentIntent = PendingIntent.getActivity(
            mContext, 50,
            notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        builder.setContentIntent(contentIntent)
        builder.setSmallIcon(R.drawable.ic_brightness)
        builder.setContentTitle("Camera Light Sensor Service")
        builder.setChannelId(mContext!!.basePackageName)
        return builder.build()
    }

    override fun onDestroy() {
        if (DEBUG) Log.d(TAG, "Destroying service")
        if (mRegistered) contentResolver!!.unregisterContentObserver(mSettingsObserver)
        mRegistered = false
        cameraDevice!!.close()
        if (session != null && avail) {
            try {
                session!!.abortCaptures()
                session!!.close()
            } catch (e: CameraAccessException) {
                Log.e(TAG, e.message)
            } catch (e2: IllegalStateException) {
                Log.e(TAG, "Session Already Closed")
            }
        }
        manager!!.unregisterAvailabilityCallback(availabilityCallback)
        avail = false
        destroy = true
        stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    private val mScreenStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                onDisplayOn()
                mServiceStarted = true
            } else if (intent.action == Intent.ACTION_SCREEN_OFF) {
                if (mServiceStarted) onDisplayOff()
                mServiceStarted = false
            }
        }
    }

    // Make a listener for settings
    private var mSettingsObserver: ContentObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                Log.i(TAG, "observer: Brightness Settings Changed")
                try {
                    if (Settings.System.getInt(
                            contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS_MODE
                        )
                        == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    ) {
                        registerReceiver(mScreenStateReceiver, screenStateFilter)
                        mRegistered = true
                        onDisplayOn()
                    } else {
                        if (mRegistered) unregisterReceiver(mScreenStateReceiver)
                        mRegistered = false
                        onDisplayOff()
                    }
                } catch (e: SettingNotFoundException) {
                    e.printStackTrace()
                }
            }

            override fun deliverSelfNotifications(): Boolean {
                return true
            }
        }

    private fun onDisplayOn() {
        if (DEBUG) Log.d(TAG, "Screen is on. Starting Service...")
        avail = true
        readyCamera()
    }

    private fun onDisplayOff() {
        if (DEBUG) Log.d(TAG, "Screen is off. Stopping Service...")
        thread.interrupt()
        avail = false
        mThreadRunning = false
    }

    private var cameraStateCallback: CameraDevice.StateCallback =
        object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                Log.d(TAG, "CameraDevice.StateCallback onOpened")
                cameraDevice = camera
                actOnReadyCameraDevice()
            }

            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "CameraDevice.StateCallback onDisconnected")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "CameraDevice.StateCallback onError $error")
            }
        }
    private var sessionStateCallback: CameraCaptureSession.StateCallback =
        object : CameraCaptureSession.StateCallback() {
            override fun onReady(session: CameraCaptureSession) {
                if (!destroy) {
                    this@CameraLightSensorService.session = session
                    try {
                        if (createCaptureRequest() == null) return
                        try {
                            session.capture(createCaptureRequest(), captureCallback, null)
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        } catch (e: IllegalStateException) {
                            Log.w(TAG, "onReady: Session is NULL")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera is in use")
                        e.printStackTrace()
                    }
                }
            }

            override fun onConfigured(session: CameraCaptureSession) {}
            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }
    private var onImageAvailableListener =
        ImageReader.OnImageAvailableListener { reader: ImageReader ->
            if (DEBUG) Log.d(TAG, "onImageAvailable: Capturing")
            val img = reader.acquireLatestImage()
            if (img != null) {
                try {
                    processImage(img)
                } catch (e: SettingNotFoundException) {
                    e.printStackTrace()
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
                img.close()
                if (DEBUG) Log.d(
                    TAG,
                    "ImageReader.OnImageAvailableListener: Closing Camera and Sessions.."
                )
                cameraDevice!!.close()
                session!!.close()
            }
        }

    @SuppressLint("MissingPermission")
    fun readyCamera() {
        manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val pickedCamera = getCamera(manager)
            manager!!.registerAvailabilityCallback(availabilityCallback, null)
            manager!!.openCamera(pickedCamera, cameraStateCallback, null)
            imageReader =
                ImageReader.newInstance(250, 250, ImageFormat.JPEG, 2 /* images buffered */)
            imageReader?.setOnImageAvailableListener(onImageAvailableListener, null)
            if (!mThreadRunning) {
                val mMyThread = Thread(thread)
                mMyThread.start()
                mThreadRunning = true
            }
            if (DEBUG) Log.d(TAG, "imageReader created")
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.message)
        }
    }

    private fun getCamera(manager: CameraManager?): String? {
        try {
            for (cameraId in manager!!.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)
                val cOrientation = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (cOrientation == CAMERA_CHOICE) {
                    return cameraId
                }
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        return null
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        mContext = applicationContext
        startForeground(50, PushNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        BatteryOptimization(mContext)
        mRegistered = false
        screenStateFilter = IntentFilter(Intent.ACTION_SCREEN_ON)
        screenStateFilter!!.addAction(Intent.ACTION_SCREEN_OFF)
        try {
            if (Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
                == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            ) {
                registerReceiver(mScreenStateReceiver, screenStateFilter)
                mRegistered = true
            }
        } catch (e: SettingNotFoundException) {
            e.printStackTrace()
        }
        val setting = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE)
        contentResolver?.registerContentObserver(setting, false, mSettingsObserver)
        if (DEBUG) Log.d(TAG, "onStartCommand flags $flags startId $startId")
        destroy = false
        avail = true
        startForeground(50, PushNotification())
        return START_STICKY
    }

    override fun onCreate() {
        if (DEBUG) Log.d(TAG, "onCreate service")
        mContext = applicationContext
        startForeground(50, PushNotification())
        super.onCreate()
    }

    fun actOnReadyCameraDevice() {
        try {
            cameraDevice!!.createCaptureSession(
                listOf(imageReader!!.surface),
                sessionStateCallback,
                null
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.message)
            mLock = false
        }
    }

    @Throws(SettingNotFoundException::class, InterruptedException::class)
    private fun processImage(image: Image) {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.capacity())
        buffer[bytes]
        val bitmapImage = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, null)
        val brightness = calculateBrightnessEstimate(bitmapImage, 5)
        AdjustBrightness(brightness)
    }

    protected fun createCaptureRequest(): CaptureRequest? {
        return try {
            val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(imageReader!!.surface)
            builder.build()
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.message)
            mLock = false
            null
        }
    }

    private var availabilityCallback: AvailabilityCallback = object : AvailabilityCallback() {
        override fun onCameraOpened(cameraId: String, packageId: String) {
            super.onCameraOpened(cameraId, packageId)
            Log.i(
                TAG, "CameraManager.AvailabilityCallback: Camera " + cameraId
                        + " Opened by Package " + packageId
            )
            mLock = true
            if (packageId == mContext!!.basePackageName) return
            avail = false
        }

        override fun onCameraUnavailable(cameraId: String) {
            if (DEBUG) Log.i(TAG, "CameraManager.AvailabilityCallback : Camera NOT Available. ")
            avail = false
            super.onCameraUnavailable(cameraId)
        }

        override fun onCameraAvailable(cameraId: String) {
            if (DEBUG) Log.i(TAG, "CameraManager.AvailabilityCallback : Camera IS Available. ")
            avail = true
            mLock = false
            super.onCameraAvailable(cameraId)
        }

        override fun onCameraClosed(cameraId: String) {
            super.onCameraClosed(cameraId)
        }
    }
    var captureCallback: CameraCaptureSession.CaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureSequenceCompleted(
                session: CameraCaptureSession,
                sequenceId: Int,
                frameNumber: Long
            ) {
                if (DEBUG) Log.d(TAG, "captureCallback: Closing Session")
                super.onCaptureSequenceCompleted(session, sequenceId, frameNumber)
                cameraDevice!!.close()
                session.close()
            }
        }

    private fun calculateBrightnessEstimate(bitmap: Bitmap, pixelSpacing: Int): Int {
        var R = 0
        var G = 0
        var B = 0
        val height = bitmap.height
        val width = bitmap.width
        var n = 0
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var i = 0
        while (i < pixels.size) {
            val color = pixels[i]
            R += Color.red(color)
            G += Color.green(color)
            B += Color.blue(color)
            n++
            i += pixelSpacing
        }
        return (R + B + G) / (n * 3)
    }

    @Throws(SettingNotFoundException::class)
    private fun AdjustBrightness(brightness: Int) {
        if (DEBUG) Log.i(TAG, "AdjustBrightness: Received Brightness Value $brightness")
        val oldbrightness =
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        if (DEBUG) Log.i(
            TAG, "AdjustBrightness: OldVal = " + oldbrightness + " NewVal = " +
                    brightness + " Adjusting.."
        )
        var newbrightness = brightness
        if (newbrightness > 255) {
            newbrightness = 255
        } else if (newbrightness < 0) {
            newbrightness = 0
        }
        Settings.System.putInt(
            contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            newbrightness
        )
    }

    private var thread = Thread {
        while (!Thread.currentThread().isInterrupted) {
            if (avail && !mLock) {
                SystemClock.sleep(DELAY.toLong())
                ContextCompat.getMainExecutor(mContext!!).execute { if (avail) readyCamera() }
            }
        }
    }

    companion object {
        protected val TAG: String = CameraLightSensorService::class.java.simpleName
        const val DEBUG = false
        protected const val CAMERA_CHOICE = CameraCharacteristics.LENS_FACING_FRONT
        private const val DELAY = 5 * 1000 // 5 Seconds
        fun BatteryOptimization(context: Context?) {
            val intent = Intent()
            val packageName = context!!.packageName
            val pm = context.getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                intent.data = Uri.parse("package:$packageName")
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            }
        }
    }
}
