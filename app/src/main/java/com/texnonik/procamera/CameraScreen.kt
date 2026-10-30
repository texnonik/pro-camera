package com.texnonik.procamera

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Timer10
import androidx.compose.material.icons.filled.Timer3
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

val Accent = Color(0xFFFFC107)
private val PanelBg = Color(0xCC111111)

private enum class Panel { ISO, SHUTTER, FOCUS, WB, EV }

@SuppressLint("ClickableViewAccessibility")
@Composable
fun CameraScreen(onOpenGallery: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val store = remember { SettingsStore(context) }
    val controller = remember { CameraController(context) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    var settings by remember { mutableStateOf(store.load()) }
    var videoMode by remember { mutableStateOf(false) }
    var caps by remember { mutableStateOf<CameraCaps?>(null) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var recordNanos by remember { mutableLongStateOf(0L) }
    var countdown by remember { mutableIntStateOf(0) }
    var capturing by remember { mutableStateOf(false) }
    var lastMedia by remember { mutableStateOf<MediaItem?>(null) }
    var liveIso by remember { mutableIntStateOf(0) }
    var liveExposure by remember { mutableLongStateOf(0L) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }

    fun update(next: ProSettings) {
        settings = next
        store.save(next)
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun refreshLast() {
        scope.launch { lastMedia = MediaRepository.latest(context) }
    }

    LaunchedEffect(settings.front, settings.ratio, settings.backCameraId, settings.frontCameraId, videoMode) {
        caps = try {
            controller.bind(lifecycleOwner, previewView, settings, videoMode)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            toast("Не удалось открыть камеру: ${e.message}")
            null
        }
        zoom = 1f
        controller.apply(settings, videoMode)
    }

    LaunchedEffect(settings, caps) { controller.apply(settings, videoMode) }
    LaunchedEffect(Unit) { lastMedia = MediaRepository.latest(context) }
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(1200)
            focusPoint = null
        }
    }

    DisposableEffect(Unit) {
        controller.onLiveExposure = { iso, exposure ->
            liveIso = iso
            liveExposure = exposure
        }
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                controller.setRotation(
                    when (orientation) {
                        in 45 until 135 -> Surface.ROTATION_270
                        in 135 until 225 -> Surface.ROTATION_180
                        in 225 until 315 -> Surface.ROTATION_90
                        else -> Surface.ROTATION_0
                    },
                )
            }
        }
        listener.enable()
        onDispose {
            listener.disable()
            controller.release()
        }
    }

    fun onShutter() {
        if (videoMode) {
            if (isRecording) {
                controller.stopRecording()
            } else {
                val started = controller.startRecording(
                    onProgress = { recordNanos = it },
                    onFinished = { uri ->
                        isRecording = false
                        recordNanos = 0L
                        if (uri != null) refreshLast() else toast("Видео не сохранено")
                    },
                )
                isRecording = started
            }
            return
        }
        if (countdown > 0 || capturing) return
        scope.launch {
            for (i in settings.timer downTo 1) {
                countdown = i
                delay(1000)
            }
            countdown = 0
            capturing = true
            controller.takePhoto { uri ->
                capturing = false
                if (uri != null) refreshLast() else toast("Фото не сохранено")
            }
        }
    }

    val c = caps
    val manualExposure = CameraController.isManualExposure(settings, c)
    val busy = isRecording || countdown > 0
    val full = settings.ratio == ProSettings.RATIO_FULL
    // Поверх видоискателя во весь экран панели полупрозрачные.
    val barBg = if (full) Color(0x66000000) else Color.Transparent

    val configuration = LocalConfiguration.current
    val screenRatioLabel = remember(configuration) {
        val w = minOf(configuration.screenWidthDp, configuration.screenHeightDp).coerceAtLeast(1)
        val h = maxOf(configuration.screenWidthDp, configuration.screenHeightDp)
        "${(h * 9f / w).roundToInt()}:9"
    }

    @Composable
    fun PreviewLayer(modifier: Modifier) {
        Box(modifier) {
            AndroidView(
                factory = {
                    (previewView.parent as? ViewGroup)?.removeView(previewView)
                    previewView.apply {
                        setOnTouchListener { v, e ->
                            if (e.action == MotionEvent.ACTION_UP) {
                                if (settings.focus < 0f) {
                                    controller.focusAt(meteringPointFactory, e.x, e.y)
                                    focusPoint = Offset(e.x, e.y)
                                }
                                v.performClick()
                            }
                            true
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (settings.grid) GridOverlay()
            focusPoint?.let { FocusRing(it) }
            if (capturing) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (full) PreviewLayer(Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding(),
        ) {
            // Верхняя панель
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(barBg)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TopIcon(
                    icon = when (settings.flash) {
                        ProSettings.FLASH_AUTO -> Icons.Filled.FlashAuto
                        ProSettings.FLASH_ON -> Icons.Filled.FlashOn
                        else -> Icons.Filled.FlashOff
                    },
                    description = "Вспышка",
                    enabled = c?.hasFlash == true,
                    active = settings.flash != ProSettings.FLASH_OFF,
                ) {
                    val modes = if (videoMode) listOf(ProSettings.FLASH_OFF, ProSettings.FLASH_ON)
                    else listOf(ProSettings.FLASH_OFF, ProSettings.FLASH_AUTO, ProSettings.FLASH_ON)
                    val idx = modes.indexOf(settings.flash)
                    update(settings.copy(flash = modes[(idx + 1) % modes.size]))
                }
                TopIcon(
                    icon = when (settings.timer) {
                        3 -> Icons.Filled.Timer3
                        10 -> Icons.Filled.Timer10
                        0 -> Icons.Filled.TimerOff
                        else -> Icons.Filled.Timer
                    },
                    description = "Таймер",
                    enabled = !videoMode && !busy,
                    active = settings.timer > 0,
                ) {
                    val values = ProSettings.TIMER_VALUES
                    update(settings.copy(timer = values[(values.indexOf(settings.timer) + 1) % values.size]))
                }
                TopText(text = if (full) screenRatioLabel else "4:3", enabled = !busy) {
                    update(
                        settings.copy(
                            ratio = if (full) ProSettings.RATIO_4_3 else ProSettings.RATIO_FULL,
                        ),
                    )
                }
                TopIcon(
                    icon = if (settings.grid) Icons.Filled.GridOn else Icons.Filled.GridOff,
                    description = "Сетка",
                    enabled = true,
                    active = settings.grid,
                ) { update(settings.copy(grid = !settings.grid)) }
                AutoButton(allAuto = settings.isAllAuto) {
                    update(settings.resetToAuto())
                    panel = null
                }
            }

            // Видоискатель (в режиме «весь экран» он лежит под всеми панелями)
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (!full) PreviewLayer(Modifier.aspectRatio(3f / 4f))
                if (countdown > 0) {
                    Text(
                        "$countdown",
                        color = Color.White,
                        fontSize = 96.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (isRecording) {
                    val total = recordNanos / 1_000_000_000L
                    Text(
                        "● %02d:%02d".format(total / 60, total % 60),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(8.dp)
                            .background(Color(0xFFD32F2F), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                if (c != null) {
                    LensRow(
                        caps = c,
                        zoom = zoom,
                        enabled = !busy,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp),
                        onLens = { id ->
                            update(
                                if (settings.front) settings.copy(frontCameraId = id)
                                else settings.copy(backCameraId = id),
                            )
                        },
                        onZoom = {
                            zoom = it
                            controller.setZoom(it)
                        },
                    )
                }
            }

            Column(Modifier.fillMaxWidth().background(barBg)) {
                // Ручные параметры
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ProChip(
                        "ISO",
                        if (settings.iso > 0 && c?.manualSensor == true) "${settings.iso}"
                        else if (liveIso > 0) "A $liveIso" else "Авто",
                        selected = panel == Panel.ISO,
                        manual = settings.iso > 0,
                    ) { panel = if (panel == Panel.ISO) null else Panel.ISO }
                    ProChip(
                        "ВЫДЕРЖКА",
                        if (settings.shutterNs > 0 && c?.manualSensor == true) ProSettings.shutterLabel(settings.shutterNs)
                        else if (liveExposure > 0) "A " + ProSettings.shutterLabel(liveExposure) else "Авто",
                        selected = panel == Panel.SHUTTER,
                        manual = settings.shutterNs > 0,
                    ) { panel = if (panel == Panel.SHUTTER) null else Panel.SHUTTER }
                    ProChip(
                        "ФОКУС",
                        focusLabel(settings.focus, c?.minFocus ?: 0f),
                        selected = panel == Panel.FOCUS,
                        manual = settings.focus >= 0f,
                    ) { panel = if (panel == Panel.FOCUS) null else Panel.FOCUS }
                    ProChip(
                        "ББ",
                        if (settings.wbMode == ProSettings.WB_KELVIN) "${settings.kelvin}K"
                        else ProSettings.WB_NAMES[settings.wbMode.coerceIn(0, ProSettings.WB_NAMES.lastIndex)],
                        selected = panel == Panel.WB,
                        manual = settings.wbMode != ProSettings.WB_AUTO,
                    ) { panel = if (panel == Panel.WB) null else Panel.WB }
                    ProChip(
                        "EV",
                        "%+.1f".format(settings.ev * (c?.evStep ?: 0f)),
                        selected = panel == Panel.EV,
                        manual = settings.ev != 0,
                    ) { panel = if (panel == Panel.EV) null else Panel.EV }
                }

                panel?.let { p ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 8.dp)
                            .background(PanelBg, RoundedCornerShape(12.dp))
                            .padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        PanelContent(p, settings, c, manualExposure) { update(it) }
                    }
                }

                // Переключатель фото / видео
                Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF222222))
                            .clickable(enabled = !busy && !capturing) {
                                panel = null
                                videoMode = !videoMode
                                if (videoMode && settings.flash == ProSettings.FLASH_AUTO) {
                                    update(settings.copy(flash = ProSettings.FLASH_OFF))
                                }
                            }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (videoMode) Icons.Filled.PhotoCamera else Icons.Filled.Videocam,
                            contentDescription = null,
                            tint = if (busy) Color.Gray else Color.White,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (videoMode) "Фото" else "Видео",
                            color = if (busy) Color.Gray else Color.White,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                // Нижняя панель
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GalleryThumb(lastMedia, enabled = !busy) { onOpenGallery() }
                    ShutterButton(videoMode = videoMode, recording = isRecording) { onShutter() }
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF222222))
                            .clickable(enabled = c?.hasFront == true && !busy) {
                                update(settings.copy(front = !settings.front))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Cameraswitch,
                            contentDescription = "Сменить камеру",
                            tint = if (c?.hasFront == true && !busy) Color.White else Color.Gray,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Выбор объектива: отдельные камеры телефона (0.6x / 1x / 2x…), а если телефон
 * отдаёт одну логическую камеру — шаги зума этой камеры.
 */
@Composable
private fun LensRow(
    caps: CameraCaps,
    zoom: Float,
    enabled: Boolean,
    modifier: Modifier,
    onLens: (String) -> Unit,
    onZoom: (Float) -> Unit,
) {
    val lensChoices = caps.lenses.size > 1
    val zoomSteps = buildList {
        if (caps.minZoom < 0.95f) add(caps.minZoom)
        add(1f)
        if (caps.maxZoom >= 2f) add(2f)
        if (caps.maxZoom >= 5f) add(5f)
    }
    if (!lensChoices && zoomSteps.size < 2) return

    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x99000000))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (lensChoices) {
            caps.lenses.forEach { lens ->
                LensChip(lens.label, selected = lens.cameraId == caps.cameraId, enabled = enabled) {
                    onLens(lens.cameraId)
                }
            }
        } else {
            zoomSteps.forEach { z ->
                LensChip(LensOption("", z).label, selected = abs(zoom - z) < 0.05f, enabled = enabled) {
                    onZoom(z)
                }
            }
        }
    }
}

@Composable
private fun LensChip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (selected) Color(0xFF333333) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) Accent else Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PanelContent(
    panel: Panel,
    s: ProSettings,
    c: CameraCaps?,
    manualExposure: Boolean,
    update: (ProSettings) -> Unit,
) {
    when (panel) {
        Panel.ISO -> {
            val range = c?.isoRange
            if (c?.manualSensor != true || range == null) {
                NotSupported("Ручной ISO не поддерживается этой камерой")
            } else {
                val values = listOf(0) + ProSettings.ISO_VALUES.filter { it in range.lower..range.upper }
                OptionsRow(values, s.iso, label = { if (it == 0) "Авто" else "$it" }) {
                    update(s.copy(iso = it))
                }
            }
        }
        Panel.SHUTTER -> {
            val range = c?.exposureRange
            if (c?.manualSensor != true || range == null) {
                NotSupported("Ручная выдержка не поддерживается этой камерой")
            } else {
                val values = listOf(0L) + ProSettings.SHUTTER_VALUES.filter { it in range.lower..range.upper }
                OptionsRow(values, s.shutterNs, label = { if (it == 0L) "Авто" else ProSettings.shutterLabel(it) }) {
                    update(s.copy(shutterNs = it))
                }
            }
        }
        Panel.FOCUS -> {
            if (c == null || c.minFocus <= 0f) {
                NotSupported("У этой камеры фиксированный фокус")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OptionChip("Авто", selected = s.focus < 0f) { update(s.copy(focus = -1f)) }
                    Spacer(Modifier.width(8.dp))
                    Text("∞", color = Color.White)
                    Slider(
                        value = s.focus.coerceAtLeast(0f),
                        onValueChange = { update(s.copy(focus = it)) },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        colors = sliderColors(),
                    )
                    Text("Макро", color = Color.White, fontSize = 12.sp)
                }
            }
        }
        Panel.WB -> {
            Column {
                OptionsRow(
                    values = ProSettings.WB_NAMES.indices.toList(),
                    selected = s.wbMode,
                    label = { ProSettings.WB_NAMES[it] },
                ) { update(s.copy(wbMode = it)) }
                if (s.wbMode == ProSettings.WB_KELVIN) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("2000K", color = Color.White, fontSize = 12.sp)
                        Slider(
                            value = s.kelvin.toFloat(),
                            onValueChange = { update(s.copy(kelvin = ((it / 100f).roundToInt() * 100))) },
                            valueRange = 2000f..10000f,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            colors = sliderColors(),
                        )
                        Text("10000K", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
        Panel.EV -> {
            val range = c?.evRange
            val step = c?.evStep ?: 0f
            if (range == null || (range.lower == 0 && range.upper == 0)) {
                NotSupported("Экспокоррекция не поддерживается")
            } else if (manualExposure) {
                NotSupported("EV работает только с авто ISO и авто выдержкой")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("%+.1f".format(range.lower * step), color = Color.White, fontSize = 12.sp)
                    Slider(
                        value = s.ev.coerceIn(range.lower, range.upper).toFloat(),
                        onValueChange = { update(s.copy(ev = it.roundToInt())) },
                        valueRange = range.lower.toFloat()..range.upper.toFloat(),
                        steps = (range.upper - range.lower - 1).coerceAtLeast(0),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        colors = sliderColors(),
                    )
                    Text("%+.1f".format(range.upper * step), color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Accent,
    activeTrackColor = Accent,
    inactiveTrackColor = Color(0xFF555555),
)

private fun focusLabel(focus: Float, minFocus: Float): String = when {
    focus < 0f -> "Авто"
    focus == 0f || minFocus <= 0f -> "∞"
    else -> {
        val meters = 1f / (focus * minFocus)
        if (meters >= 1f) "%.1f м".format(meters) else "${(meters * 100).roundToInt()} см"
    }
}

@Composable
private fun <T> OptionsRow(values: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        values.forEach { v ->
            OptionChip(label(v), selected = v == selected) { onSelect(v) }
            Spacer(Modifier.width(6.dp))
        }
    }
}

@Composable
private fun OptionChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Color.Black else Color.White,
        fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Accent else Color(0xFF2A2A2A))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun NotSupported(text: String) {
    Text(text, color = Color.LightGray, fontSize = 13.sp, textAlign = TextAlign.Center)
}

@Composable
private fun ProChip(label: String, value: String, selected: Boolean, manual: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF333333) else Color.Transparent)
            .border(
                BorderStroke(1.dp, if (selected) Accent else Color.Transparent),
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Color.LightGray, fontSize = 10.sp)
        Text(
            value,
            color = if (manual) Accent else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun TopIcon(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    active: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            icon,
            contentDescription = description,
            tint = when {
                !enabled -> Color.DarkGray
                active -> Accent
                else -> Color.White
            },
        )
    }
}

@Composable
private fun TopText(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (enabled) Color.White else Color.DarkGray,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
    )
}

@Composable
private fun AutoButton(allAuto: Boolean, onClick: () -> Unit) {
    Text(
        "АВТО",
        color = if (allAuto) Color.Black else Accent,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (allAuto) Accent else Color.Transparent)
            .border(1.5.dp, Accent, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun ShutterButton(videoMode: Boolean, recording: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(80.dp)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            recording -> Box(
                Modifier
                    .size(30.dp)
                    .background(Color(0xFFE53935), RoundedCornerShape(6.dp)),
            )
            videoMode -> Box(
                Modifier
                    .size(62.dp)
                    .background(Color(0xFFE53935), CircleShape),
            )
            else -> Box(
                Modifier
                    .size(64.dp)
                    .background(Color.White, CircleShape),
            )
        }
    }
}

@Composable
private fun GalleryThumb(item: MediaItem?, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color(0xFF222222))
            .border(2.dp, Color.White, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (item != null) {
            AsyncImage(
                model = item.uri,
                contentDescription = "Галерея",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = "Галерея", tint = Color.White)
        }
    }
}

@Composable
private fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val color = Color.White.copy(alpha = 0.35f)
        val stroke = 1.dp.toPx()
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
            drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
        }
    }
}

@Composable
private fun FocusRing(point: Offset) {
    Canvas(
        Modifier
            .size(72.dp)
            .offset { IntOffset(point.x.roundToInt() - 36.dp.roundToPx(), point.y.roundToInt() - 36.dp.roundToPx()) },
    ) {
        drawCircle(Accent, radius = size.minDimension / 2f - 2.dp.toPx(), style = Stroke(2.dp.toPx()))
    }
}
