package com.texnonik.procamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Что умеет текущая камера. */
data class CameraCaps(
    val manualSensor: Boolean,
    val manualPostProcessing: Boolean,
    val isoRange: Range<Int>?,
    val exposureRange: Range<Long>?,
    /** В диоптриях; 0 = фокус фиксированный. */
    val minFocus: Float,
    val evRange: Range<Int>,
    val evStep: Float,
    val hasFlash: Boolean,
    val hasFront: Boolean,
    /** Объективы с той же стороны, что и текущая камера (если телефон их открывает). */
    val lenses: List<LensOption>,
    val cameraId: String,
    val minZoom: Float,
    val maxZoom: Float,
)

/** Отдельная камера телефона; [factor] — увеличение относительно основной (0.6x, 1x, 2x…). */
data class LensOption(val cameraId: String, val factor: Float) {
    val label: String
        get() = if (kotlin.math.abs(factor - factor.roundToInt()) < 0.05f) {
            "${factor.roundToInt()}x"
        } else {
            "%.1fx".format(factor)
        }
}

/** CameraX + Camera2 interop: привязка, ручные параметры, фото и видео. */
@OptIn(ExperimentalCamera2Interop::class)
class CameraController(private val context: Context) {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var rotation = Surface.ROTATION_0
    private var caps: CameraCaps? = null

    /** Последние значения автоматики — для подписи «A ISO 320» и для режима Кельвин. */
    @Volatile private var awbGains: RggbChannelVector? = null
    @Volatile private var awbTransform: ColorSpaceTransform? = null
    @Volatile private var lastLiveUpdate = 0L
    var onLiveExposure: ((iso: Int, exposureNs: Long) -> Unit)? = null

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (request.get(CaptureRequest.CONTROL_AWB_MODE) != CaptureRequest.CONTROL_AWB_MODE_OFF) {
                result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { awbGains = it }
                result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { awbTransform = it }
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastLiveUpdate < 400) return
            lastLiveUpdate = now
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            mainHandler.post { onLiveExposure?.invoke(iso, exposure) }
        }
    }

    private suspend fun getProvider(): ProcessCameraProvider {
        provider?.let { return it }
        return suspendCoroutine<ProcessCameraProvider> { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({ cont.resume(future.get()) }, mainExecutor)
        }.also { provider = it }
    }

    suspend fun bind(
        owner: LifecycleOwner,
        previewView: PreviewView,
        s: ProSettings,
        videoMode: Boolean,
    ): CameraCaps {
        val p = getProvider()
        val hasFront = runCatching { p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
        val hasBack = runCatching { p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
        val front = (s.front && hasFront) || !hasBack
        val lenses = lensesFor(p, front)
        val wantedId = if (front) s.frontCameraId else s.backCameraId
        val selector = if (wantedId.isNotEmpty() && lenses.any { it.cameraId == wantedId }) {
            CameraSelector.Builder()
                .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == wantedId } }
                .build()
        } else if (front) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val full = s.ratio == ProSettings.RATIO_FULL
        // Во весь экран: берём 16:9 с сенсора и обрезаем по видоискателю через ViewPort,
        // чтобы снимок совпадал с тем, что видно на экране.
        val viewPort = if (full) awaitViewPort(previewView) else null
        val aspect = if (full) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(aspect, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .build()

        val previewBuilder = Preview.Builder().setResolutionSelector(resolution)
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(captureCallback)
        val preview = previewBuilder.build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        p.unbindAll()
        imageCapture = null
        videoCapture = null

        val useCase: UseCase = if (videoMode) {
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)),
                )
                .setAspectRatio(aspect)
                .build()
            VideoCapture.withOutput(recorder).also {
                it.targetRotation = rotation
                videoCapture = it
            }
        } else {
            ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(resolution)
                .setJpegQuality(ProSettings.JPEG_QUALITY)
                .setFlashMode(flashMode(s.flash))
                .setTargetRotation(rotation)
                .build()
                .also { imageCapture = it }
        }
        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(useCase)
            .apply { viewPort?.let { setViewPort(it) } }
            .build()
        val cam = p.bindToLifecycle(owner, selector, group)
        camera = cam
        return readCaps(cam, hasFront, lenses).also { caps = it }
    }

    private suspend fun awaitViewPort(previewView: PreviewView): ViewPort? {
        delay(50)
        repeat(60) {
            if (previewView.isAttachedToWindow) previewView.viewPort?.let { return it }
            delay(16)
        }
        return null
    }

    /** Все камеры с нужной стороны, отсортированные по фокусному расстоянию. */
    private fun lensesFor(p: ProcessCameraProvider, front: Boolean): List<LensOption> {
        val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val found = p.availableCameraInfos.mapNotNull { info ->
            val c2 = Camera2CameraInfo.from(info)
            if (c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING) != facing) return@mapNotNull null
            val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull() ?: return@mapNotNull null
            val sensor = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                ?: return@mapNotNull null
            // Эквивалентное фокусное расстояние (35 мм), чтобы сравнивать разные сенсоры.
            c2.cameraId to focal * 43.27f / hypot(sensor.width, sensor.height)
        }
        if (found.isEmpty()) return emptyList()
        val main = found.first().second
        return found
            .map { LensOption(it.first, it.second / main) }
            .distinctBy { (it.factor * 10).roundToInt() }
            .sortedBy { it.factor }
    }

    fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    private fun readCaps(cam: Camera, hasFront: Boolean, lenses: List<LensOption>): CameraCaps {
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val expRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocus = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val available = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: IntArray(0)
        val exposure = cam.cameraInfo.exposureState
        return CameraCaps(
            manualSensor = available.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) &&
                isoRange != null && expRange != null,
            manualPostProcessing = available.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING,
            ),
            isoRange = isoRange,
            exposureRange = expRange,
            minFocus = minFocus,
            evRange = exposure.exposureCompensationRange,
            evStep = exposure.exposureCompensationStep.toFloat(),
            hasFlash = cam.cameraInfo.hasFlashUnit(),
            hasFront = hasFront,
            lenses = lenses,
            cameraId = info.cameraId,
            minZoom = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1f,
            maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f,
        )
    }

    /** Применяет ручные параметры к текущей сессии. */
    fun apply(s: ProSettings, videoMode: Boolean) {
        val cam = camera ?: return
        val c = caps ?: return
        val b = CaptureRequestOptions.Builder()

        val manualExposure = isManualExposure(s, c)
        if (manualExposure) {
            val isoRange = c.isoRange!!
            val expRange = c.exposureRange!!
            val iso = isoRange.clamp(if (s.iso > 0) s.iso else DEFAULT_ISO)
            val exposure = expRange.clamp(if (s.shutterNs > 0L) s.shutterNs else DEFAULT_EXPOSURE_NS)
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
        }

        if (s.focus >= 0f && c.minFocus > 0f) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, s.focus * c.minFocus)
        }

        when (s.wbMode) {
            ProSettings.WB_INCANDESCENT -> b.awb(CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT)
            ProSettings.WB_FLUORESCENT -> b.awb(CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT)
            ProSettings.WB_DAYLIGHT -> b.awb(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT)
            ProSettings.WB_CLOUDY -> b.awb(CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT)
            ProSettings.WB_KELVIN -> if (c.manualPostProcessing) {
                b.awb(CaptureRequest.CONTROL_AWB_MODE_OFF)
                b.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
                )
                b.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinGains(s.kelvin))
                b.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_TRANSFORM, awbTransform ?: IDENTITY)
            } else {
                b.awb(nearestPreset(s.kelvin))
            }
        }

        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())

        if (c.evRange.lower != 0 || c.evRange.upper != 0) {
            cam.cameraControl.setExposureCompensationIndex(if (manualExposure) 0 else c.evRange.clamp(s.ev))
        }
        imageCapture?.flashMode = flashMode(s.flash)
        if (c.hasFlash) cam.cameraControl.enableTorch(videoMode && s.flash == ProSettings.FLASH_ON)
    }

    fun setRotation(r: Int) {
        if (r == rotation || recording != null) return
        rotation = r
        imageCapture?.targetRotation = r
        videoCapture?.targetRotation = r
    }

    fun focusAt(factory: MeteringPointFactory, x: Float, y: Float) {
        val cam = camera ?: return
        val action = FocusMeteringAction.Builder(factory.createPoint(x, y))
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    fun takePhoto(onDone: (Uri?) -> Unit) {
        val ic = imageCapture ?: return onDone(null)
        val values = MediaRepository.contentValues(MediaRepository.newFileName("IMG"), "image/jpeg", ".jpg")
        val options = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ).build()
        ic.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) = onDone(output.savedUri)

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Photo capture failed", exception)
                onDone(null)
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun startRecording(onProgress: (Long) -> Unit, onFinished: (Uri?) -> Unit): Boolean {
        val vc = videoCapture ?: return false
        val values = MediaRepository.contentValues(MediaRepository.newFileName("VID"), "video/mp4", ".mp4")
        val options = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).setContentValues(values).build()

        var pending = vc.output.prepareRecording(context, options)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(mainExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Status -> onProgress(event.recordingStats.recordedDurationNanos)
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    val uri = event.outputResults.outputUri
                    if (event.hasError()) Log.w(TAG, "Recording finished with error ${event.error}", event.cause)
                    onFinished(if (uri == Uri.EMPTY) null else uri)
                }
            }
        }
        return true
    }

    fun stopRecording() {
        recording?.stop()
    }

    fun release() {
        recording?.stop()
        recording = null
        onLiveExposure = null
        provider?.unbindAll()
    }

    private fun CaptureRequestOptions.Builder.awb(mode: Int) {
        setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, mode)
    }

    /**
     * Усиления каналов для заданной цветовой температуры: берём последний результат
     * автобаланса как опорные ~5500K и сдвигаем по кривой абсолютно чёрного тела.
     */
    private fun kelvinGains(kelvin: Int): RggbChannelVector {
        val base = awbGains ?: RggbChannelVector(2.0f, 1f, 1f, 1.6f)
        val ref = blackbody(5500)
        val target = blackbody(kelvin)
        val red = (ref[0] / ref[1]) / (target[0] / target[1])
        val blue = (ref[2] / ref[1]) / (target[2] / target[1])
        return RggbChannelVector(
            (base.red * red).toFloat().coerceIn(0.5f, 8f),
            base.greenEven,
            base.greenOdd,
            (base.blue * blue).toFloat().coerceIn(0.5f, 8f),
        )
    }

    private fun blackbody(kelvin: Int): DoubleArray {
        val t = kelvin / 100.0
        val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
        val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
        val b = when {
            t >= 66 -> 255.0
            t <= 19 -> 0.0
            else -> 138.5177312231 * ln(t - 10) - 305.0447927307
        }
        return doubleArrayOf(r.coerceIn(1.0, 255.0), g.coerceIn(1.0, 255.0), b.coerceIn(1.0, 255.0))
    }

    private fun nearestPreset(kelvin: Int) = when {
        kelvin < 3500 -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
        kelvin < 4800 -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
        kelvin < 6200 -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
        else -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
    }

    companion object {
        private const val TAG = "CameraController"
        const val DEFAULT_ISO = 200
        const val DEFAULT_EXPOSURE_NS = 1_000_000_000L / 60

        private val IDENTITY = ColorSpaceTransform(
            intArrayOf(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1),
        )

        fun isManualExposure(s: ProSettings, c: CameraCaps?) =
            c != null && c.manualSensor && (s.iso > 0 || s.shutterNs > 0L)

        fun flashMode(flash: Int) = when (flash) {
            ProSettings.FLASH_AUTO -> ImageCapture.FLASH_MODE_AUTO
            ProSettings.FLASH_ON -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }
}
