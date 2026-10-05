package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground Service for in-memory screen capture using MediaProjection and ImageReader.
 *
 * Targets:
 * - Device: OPPO CPH2631
 * - Resolution: Landscape (1604 x 720 pixels)
 * - Crosshair target coordinates: X = 801, Y = 359
 * - Region of Interest (ROI): 20x20 pixels centered at (801, 359)
 * - Delay: ~30ms loop interval
 *
 * Direct ByteBuffer processing: Image is closed immediately after processing each frame.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var captureLoopJob: Job? = null

    companion object {
        const val CHANNEL_ID = "screen_capture_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.ScreenCaptureService.ACTION_START"
        const val ACTION_STOP = "com.example.ScreenCaptureService.ACTION_STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopScreenCapture()
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

            if (resultCode != 0 && resultData != null) {
                startForegroundWithNotification()
                initMediaProjection(resultCode, resultData)
            } else {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Color Detection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors crosshair coordinates on screen for color detection"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Color Detector Active")
            .setContentText("Target: (801, 359) | OPPO CPH2631")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mpManager == null) {
            stopSelf()
            return
        }

        try {
            val projection = mpManager.getMediaProjection(resultCode, data)
            if (projection == null) {
                stopSelf()
                return
            }
            mediaProjection = projection

            // In Android 14+, callback MUST be registered before createVirtualDisplay
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    stopScreenCapture()
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))

            setupVirtualDisplay(projection)
            DetectionState.setServiceRunning(true)
            startCaptureLoop()

            // Also ensure OverlayService is started
            OverlayService.start(this)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun setupVirtualDisplay(projection: MediaProjection) {
        val width = DetectionState.TARGET_WIDTH    // 1604
        val height = DetectionState.TARGET_HEIGHT  // 720
        val dpi = resources.displayMetrics.densityDpi

        // In-memory ImageReader with 2 buffers to prevent starvation
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenColorCapture",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )
    }

    private fun startCaptureLoop() {
        captureLoopJob?.cancel()
        captureLoopJob = serviceScope.launch {
            val targetCenterX = DetectionState.TARGET_X // 801
            val targetCenterY = DetectionState.TARGET_Y // 359
            val radius = DetectionState.ROI_RADIUS      // 10

            var lastFpsTimestamp = System.currentTimeMillis()
            var framesCounted = 0
            var currentFps = 0
            var totalProcessedFrames = 0L

            while (isActive) {
                val loopStartTime = System.currentTimeMillis()
                val reader = imageReader

                if (reader != null) {
                    var image: android.media.Image? = null
                    try {
                        image = reader.acquireLatestImage()
                        if (image != null) {
                            val planes = image.planes
                            if (planes.isNotEmpty()) {
                                val plane = planes[0]
                                val buffer = plane.buffer
                                val pixelStride = plane.pixelStride
                                val rowStride = plane.rowStride
                                val imgWidth = image.width
                                val imgHeight = image.height

                                // 20x20 Region of Interest centered around (801, 359)
                                val startX = (targetCenterX - radius).coerceIn(0, imgWidth - 1)
                                val endX = (targetCenterX + radius - 1).coerceIn(0, imgWidth - 1)
                                val startY = (targetCenterY - radius).coerceIn(0, imgHeight - 1)
                                val endY = (targetCenterY + radius - 1).coerceIn(0, imgHeight - 1)

                                var redPixels = 0
                                var greenPixels = 0
                                var otherPixels = 0

                                // Center pixel sampling (801, 359)
                                val centerOffset = targetCenterY * rowStride + targetCenterX * pixelStride
                                var centerR = 0
                                var centerG = 0
                                var centerB = 0
                                if (centerOffset + 2 < buffer.capacity()) {
                                    centerR = buffer.get(centerOffset).toInt() and 0xFF
                                    centerG = buffer.get(centerOffset + 1).toInt() and 0xFF
                                    centerB = buffer.get(centerOffset + 2).toInt() and 0xFF
                                }

                                // Iterate pixels in ROI (20x20 = 400 pixels max)
                                for (y in startY..endY) {
                                    val rowOffset = y * rowStride
                                    for (x in startX..endX) {
                                        val pixelIndex = rowOffset + x * pixelStride
                                        if (pixelIndex + 2 < buffer.capacity()) {
                                            val r = buffer.get(pixelIndex).toInt() and 0xFF
                                            val g = buffer.get(pixelIndex + 1).toInt() and 0xFF
                                            val b = buffer.get(pixelIndex + 2).toInt() and 0xFF

                                            // Thresholds for Detection as required:
                                            // RED (Enemy): RGB where R > 180, G < 100, B < 100
                                            // GREEN (Safe): RGB where G > 150, R < 100
                                            // OTHER: Anything else
                                            if (r > 180 && g < 100 && b < 100) {
                                                redPixels++
                                            } else if (g > 150 && r < 100) {
                                                greenPixels++
                                            } else {
                                                otherPixels++
                                            }
                                        }
                                    }
                                }

                                // Dominant color determination:
                                // At least 10 detected pixels in the 20x20 ROI (~2.5%) triggers the state
                                val result = when {
                                    redPixels >= 10 && redPixels >= greenPixels -> DetectionResult.RED
                                    greenPixels >= 10 && greenPixels > redPixels -> DetectionResult.GREEN
                                    else -> DetectionResult.SCANNING
                                }

                                totalProcessedFrames++
                                framesCounted++
                                val now = System.currentTimeMillis()
                                if (now - lastFpsTimestamp >= 1000L) {
                                    currentFps = framesCounted
                                    framesCounted = 0
                                    lastFpsTimestamp = now
                                }

                                DetectionState.updateMetrics(
                                    DetectionMetrics(
                                        result = result,
                                        redPixels = redPixels,
                                        greenPixels = greenPixels,
                                        otherPixels = otherPixels,
                                        centerR = centerR,
                                        centerG = centerG,
                                        centerB = centerB,
                                        fps = currentFps,
                                        frameCount = totalProcessedFrames,
                                        lastUpdateTimeMs = now
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        // Suppress frame processing hiccups
                    } finally {
                        // CRITICAL: Close image immediately to return buffer to pool
                        image?.close()
                    }
                }

                // Throttle loop to ~30ms to prevent CPU overload while maintaining ~33 FPS
                val loopElapsed = System.currentTimeMillis() - loopStartTime
                val delayTime = (30L - loopElapsed).coerceAtLeast(10L)
                delay(delayTime)
            }
        }
    }

    private fun stopScreenCapture() {
        captureLoopJob?.cancel()
        captureLoopJob = null

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        imageReader = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaProjection = null

        DetectionState.setServiceRunning(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScreenCapture()
        serviceJob.cancel()
    }
}
