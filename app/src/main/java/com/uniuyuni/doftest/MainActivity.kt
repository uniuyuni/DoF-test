package com.uniuyuni.doftest

import android.content.Context
import android.view.MotionEvent
import android.view.ViewParent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.dp
import com.uniuyuni.doftest.ui.theme.DoFTestTheme
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DoFTestTheme {
                DofCalculatorApp()
            }
        }
    }
}

data class SensorFormat(
    val label: String,
    val widthMm: Double,
    val heightMm: Double,
)

data class DofResult(
    val cocMm: Double,
    val hyperfocalMm: Double,
    val nearLimitMm: Double,
    val farLimitMm: Double?,
) {
    val totalDepthMm: Double?
        get() = farLimitMm?.minus(nearLimitMm)

    fun frontDepthMm(subjectDistanceMm: Double): Double =
        (subjectDistanceMm - nearLimitMm).coerceAtLeast(0.0)

    fun backDepthMm(subjectDistanceMm: Double): Double? =
        farLimitMm?.minus(subjectDistanceMm)?.coerceAtLeast(0.0)
}

data class DofPreset(
    val id: Long,
    val name: String,
    val sensorLabel: String,
    val megapixels: Double,
    val focalLengthMm: Int,
    val aperture: Double,
    val subjectDistanceM: Double,
    val cocModeName: String,
    val considerAiryDisk: Boolean,
)

data class DofSessionState(
    val sensorLabel: String,
    val megapixels: Double,
    val focalLengthMm: Int,
    val aperture: Double,
    val subjectDistanceM: Double,
    val cocModeName: String,
    val considerAiryDisk: Boolean,
    val detailsExpanded: Boolean,
)

private data class HyperfocalDisplayInfo(
    val nearText: String,
    val hyperfocalText: String,
)

enum class CocMode(val label: String) {
    PIXEL("ピクセル"),
    PRINT("プリント"),
}

object DofCalculator {
    fun calculate(
        sensorFormat: SensorFormat,
        megapixels: Double,
        focalLengthMm: Double,
        aperture: Double,
        subjectDistanceM: Double,
        cocMode: CocMode,
        considerAiryDisk: Boolean,
    ): DofResult {
        val cocMm = calculateCircleOfConfusion(
            sensorFormat = sensorFormat,
            megapixels = megapixels,
            aperture = aperture,
            cocMode = cocMode,
            considerAiryDisk = considerAiryDisk,
        )
        val focalLength = focalLengthMm.coerceAtLeast(0.1)
        val subjectDistanceMm = (subjectDistanceM * 1000.0).coerceAtLeast(focalLength + 1.0)
        val hyperfocalMm = (focalLength * focalLength) / (aperture * cocMm) + focalLength
        val nearLimitMm = calculateNearLimitMm(
            hyperfocalMm = hyperfocalMm,
            subjectDistanceMm = subjectDistanceMm,
            focalLengthMm = focalLength,
        )
        val farLimitMm = if (subjectDistanceMm >= hyperfocalMm - HYPERFOCAL_TOLERANCE_MM) {
            null
        } else {
            val denominatorFar = hyperfocalMm - (subjectDistanceMm - focalLength)
            (hyperfocalMm * subjectDistanceMm) / denominatorFar
        }

        return DofResult(
            cocMm = cocMm,
            hyperfocalMm = hyperfocalMm,
            nearLimitMm = nearLimitMm,
            farLimitMm = farLimitMm,
        )
    }

    private fun calculateCircleOfConfusion(
        sensorFormat: SensorFormat,
        megapixels: Double,
        aperture: Double,
        cocMode: CocMode,
        considerAiryDisk: Boolean,
    ): Double {
        val classicalCocMm = sensorFormat.diagonalMm / 1500.0
        val totalPixels = (megapixels * 1_000_000.0).coerceAtLeast(1.0)
        val aspectRatio = sensorFormat.widthMm / sensorFormat.heightMm
        val sensorWidthPixels = sqrt(totalPixels * aspectRatio)
        val pixelPitchMm = sensorFormat.widthMm / sensorWidthPixels
        val baseCocMm = when (cocMode) {
            CocMode.PIXEL -> pixelPitchMm
            CocMode.PRINT -> classicalCocMm
        }
        if (!considerAiryDisk) {
            return baseCocMm
        }
        val airyDiskDiameterMm = 2.44 * DEFAULT_WAVELENGTH_MM * aperture
        return maxOf(baseCocMm, airyDiskDiameterMm)
    }

    fun calculateNearLimitMm(
        hyperfocalMm: Double,
        subjectDistanceMm: Double,
        focalLengthMm: Double,
    ): Double {
        val denominatorNear = hyperfocalMm + (subjectDistanceMm - focalLengthMm)
        return (hyperfocalMm * subjectDistanceMm) / denominatorNear
    }

    fun hyperfocalFrontDepthMm(hyperfocalMm: Double, focalLengthMm: Double): Double {
        val nearAtHyperfocalMm = calculateNearLimitMm(
            hyperfocalMm = hyperfocalMm,
            subjectDistanceMm = hyperfocalMm,
            focalLengthMm = focalLengthMm,
        )
        return (hyperfocalMm - nearAtHyperfocalMm).coerceAtLeast(0.0)
    }

    private const val DEFAULT_WAVELENGTH_MM = 0.00055
    private const val HYPERFOCAL_TOLERANCE_MM = 50.0
}

private val SensorFormat.diagonalMm: Double
    get() = sqrt((widthMm * widthMm) + (heightMm * heightMm))

private class PresetRepository(context: Context) {
    private val sharedPreferences =
        context.getSharedPreferences("dof_presets", Context.MODE_PRIVATE)

    fun loadPresets(): List<DofPreset> {
        val raw = sharedPreferences.getString(KEY_PRESETS, "[]") ?: "[]"
        return runCatching {
            val jsonArray = JSONArray(raw)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(index)
                    add(
                        DofPreset(
                            id = item.getLong("id"),
                            name = item.getString("name"),
                            sensorLabel = item.getString("sensorLabel"),
                            megapixels = item.getDouble("megapixels"),
                            focalLengthMm = item.getInt("focalLengthMm"),
                            aperture = item.getDouble("aperture"),
                            subjectDistanceM = item.getDouble("subjectDistanceM"),
                            cocModeName = item.optString("cocModeName", CocMode.PRINT.name),
                            considerAiryDisk = item.optBoolean("considerAiryDisk", false),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun savePreset(presets: List<DofPreset>) {
        val jsonArray = JSONArray()
        presets.distinctBy { it.id }.sortedByDescending { it.id }.forEach { item ->
            jsonArray.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("sensorLabel", item.sensorLabel)
                    .put("megapixels", item.megapixels)
                    .put("focalLengthMm", item.focalLengthMm)
                    .put("aperture", item.aperture)
                    .put("subjectDistanceM", item.subjectDistanceM)
                    .put("cocModeName", item.cocModeName)
                    .put("considerAiryDisk", item.considerAiryDisk)
            )
        }
        sharedPreferences.edit().putString(KEY_PRESETS, jsonArray.toString()).apply()
    }

    fun loadSessionState(): DofSessionState? {
        val raw = sharedPreferences.getString(KEY_SESSION_STATE, null) ?: return null
        return runCatching {
            val item = JSONObject(raw)
            DofSessionState(
                sensorLabel = item.getString("sensorLabel"),
                megapixels = item.getDouble("megapixels"),
                focalLengthMm = item.getInt("focalLengthMm"),
                aperture = item.getDouble("aperture"),
                subjectDistanceM = item.getDouble("subjectDistanceM"),
                cocModeName = item.optString("cocModeName", CocMode.PRINT.name),
                considerAiryDisk = item.optBoolean("considerAiryDisk", false),
                detailsExpanded = item.optBoolean("detailsExpanded", false),
            )
        }.getOrNull()
    }

    fun saveSessionState(state: DofSessionState) {
        val json = JSONObject()
            .put("sensorLabel", state.sensorLabel)
            .put("megapixels", state.megapixels)
            .put("focalLengthMm", state.focalLengthMm)
            .put("aperture", state.aperture)
            .put("subjectDistanceM", state.subjectDistanceM)
            .put("cocModeName", state.cocModeName)
            .put("considerAiryDisk", state.considerAiryDisk)
            .put("detailsExpanded", state.detailsExpanded)
        sharedPreferences.edit().putString(KEY_SESSION_STATE, json.toString()).apply()
    }

    companion object {
        private const val KEY_PRESETS = "saved_presets"
        private const val KEY_SESSION_STATE = "session_state"
    }
}

private val sensorFormats = listOf(
    SensorFormat("1インチ", 13.2, 8.8),
    SensorFormat("マイクロフォーサーズ", 17.3, 13.0),
    SensorFormat("APS-C", 23.6, 15.7),
    SensorFormat("キャノンAPS-C", 22.3, 14.9),
    SensorFormat("フルフレーム", 36.0, 24.0),
    SensorFormat("ラージフォーマット", 44.0, 33.0),
)

private val apertureStops = listOf(1.0, 1.4, 2.0, 2.8, 4.0, 5.6, 8.0, 11.0, 16.0, 22.0, 32.0)

private fun cocModeFromName(name: String): CocMode =
    CocMode.entries.firstOrNull { it.name == name } ?: CocMode.PRINT

private fun isAtSubjectDistanceMax(currentValue: Float, maxValue: Float): Boolean =
    kotlin.math.abs(currentValue - maxValue) <= 0.0001f

@Composable
fun DofCalculatorApp() {
    val context = LocalContext.current
    val repository = remember(context) { PresetRepository(context) }
    val presets = remember { mutableStateListOf<DofPreset>().apply { addAll(repository.loadPresets()) } }
    val savedSession = remember(repository) { repository.loadSessionState() }

    var sensorIndex by rememberSaveable {
        mutableStateOf(
            savedSession?.let { state ->
                sensorFormats.indexOfFirst { it.label == state.sensorLabel }.takeIf { it >= 0 } ?: 4
            } ?: 4
        )
    }
    var megapixelsInput by rememberSaveable {
        mutableStateOf(savedSession?.megapixels?.let(::formatMegapixelsInput) ?: "24")
    }
    var focalLengthMm by rememberSaveable {
        mutableStateOf(savedSession?.focalLengthMm?.toFloat() ?: 50f)
    }
    var apertureIndex by rememberSaveable {
        mutableStateOf(
            savedSession?.let { state ->
                apertureStops.indexOfFirst { it == state.aperture }.takeIf { it >= 0 }
                    ?: apertureStops.indexOf(4.0)
            } ?: apertureStops.indexOf(4.0)
        )
    }
    var subjectDistanceM by rememberSaveable {
        mutableStateOf(savedSession?.subjectDistanceM?.toFloat() ?: 0.5f)
    }
    var cocModeName by rememberSaveable { mutableStateOf(savedSession?.cocModeName ?: CocMode.PRINT.name) }
    var considerAiryDisk by rememberSaveable { mutableStateOf(savedSession?.considerAiryDisk ?: false) }
    var presetName by rememberSaveable { mutableStateOf("") }
    var sensorMenuExpanded by remember { mutableStateOf(false) }
    var detailsExpanded by rememberSaveable { mutableStateOf(savedSession?.detailsExpanded ?: false) }
    var isSubjectDragActive by remember { mutableStateOf(false) }

    val sensorFormat = sensorFormats[sensorIndex]
    val aperture = apertureStops[apertureIndex]
    val cocMode = remember(cocModeName) { cocModeFromName(cocModeName) }
    val megapixels = megapixelsInput.toDoubleOrNull()?.coerceAtLeast(0.1)
    val rawResult = megapixels?.let {
        DofCalculator.calculate(
            sensorFormat = sensorFormat,
            megapixels = it,
            focalLengthMm = focalLengthMm.toDouble(),
            aperture = aperture,
            subjectDistanceM = subjectDistanceM.toDouble(),
            cocMode = cocMode,
            considerAiryDisk = considerAiryDisk,
        )
    }
    val subjectDistanceMaxM = (rawResult?.hyperfocalMm?.div(1000.0) ?: 30.0).coerceAtLeast(0.1).toFloat()
    val effectiveSubjectDistanceM = if (
        rawResult != null && isAtSubjectDistanceMax(subjectDistanceM, subjectDistanceMaxM)
    ) {
        rawResult.hyperfocalMm / 1000.0
    } else {
        subjectDistanceM.toDouble()
    }
    val result = megapixels?.let {
        DofCalculator.calculate(
            sensorFormat = sensorFormat,
            megapixels = it,
            focalLengthMm = focalLengthMm.toDouble(),
            aperture = aperture,
            subjectDistanceM = effectiveSubjectDistanceM,
            cocMode = cocMode,
            considerAiryDisk = considerAiryDisk,
        )
    }

    LaunchedEffect(subjectDistanceMaxM) {
        if (subjectDistanceM > subjectDistanceMaxM) {
            subjectDistanceM = subjectDistanceMaxM
        }
    }

    LaunchedEffect(
        sensorIndex,
        megapixelsInput,
        focalLengthMm,
        apertureIndex,
        subjectDistanceM,
        cocModeName,
        considerAiryDisk,
        detailsExpanded,
    ) {
        val megapixelsValue = megapixelsInput.toDoubleOrNull() ?: return@LaunchedEffect
        repository.saveSessionState(
            DofSessionState(
                sensorLabel = sensorFormats[sensorIndex].label,
                megapixels = megapixelsValue,
                focalLengthMm = focalLengthMm.toInt(),
                aperture = apertureStops[apertureIndex],
                subjectDistanceM = subjectDistanceM.toDouble(),
                cocModeName = cocModeName,
                considerAiryDisk = considerAiryDisk,
                detailsExpanded = detailsExpanded,
            )
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        )
                    )
                )
                .verticalScroll(rememberScrollState(), enabled = !isSubjectDragActive)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            InputCard(
                sensorFormat = sensorFormat,
                detailsExpanded = detailsExpanded,
                onDetailsExpandedChange = { detailsExpanded = it },
                sensorMenuExpanded = sensorMenuExpanded,
                onSensorMenuExpandedChange = { sensorMenuExpanded = it },
                onSensorSelected = { sensorIndex = it },
                megapixelsInput = megapixelsInput,
                onMegapixelsChange = { value ->
                    megapixelsInput = value.filter { char -> char.isDigit() || char == '.' }.take(6)
                },
                cocMode = cocMode,
                onCocModeChange = { cocModeName = it.name },
                considerAiryDisk = considerAiryDisk,
                onConsiderAiryDiskChange = { considerAiryDisk = it },
                focalLengthMm = focalLengthMm,
                onFocalLengthChange = { focalLengthMm = it },
                apertureIndex = apertureIndex,
                onApertureIndexChange = { apertureIndex = it },
                subjectDistanceM = subjectDistanceM,
                subjectDistanceMaxM = subjectDistanceMaxM,
                onSubjectDistanceChange = { subjectDistanceM = it },
                megapixelsError = megapixels == null,
            )

            result?.let {
                VisualizationCard(
                    subjectDistanceM = effectiveSubjectDistanceM,
                    subjectDistanceMaxM = subjectDistanceMaxM,
                    onSubjectDistanceChange = { subjectDistanceM = it.toFloat() },
                    onSubjectDragActiveChange = { isSubjectDragActive = it },
                    focalLengthMm = focalLengthMm.toDouble(),
                    result = it,
                )
            }

            SummaryCard(
                sensorFormat = sensorFormat,
                subjectDistanceM = effectiveSubjectDistanceM,
                megapixels = megapixels,
                result = result,
            )

            PresetCard(
                presetName = presetName,
                onPresetNameChange = { presetName = it.take(20) },
                presets = presets,
                onSaveClick = {
                    val resolvedMegapixels = megapixels ?: return@PresetCard
                    val name = presetName.ifBlank {
                        "${sensorFormat.label} ${focalLengthMm.toInt()}mm F${formatAperture(aperture)}"
                    }
                    val preset = DofPreset(
                        id = System.currentTimeMillis(),
                        name = name,
                        sensorLabel = sensorFormat.label,
                        megapixels = resolvedMegapixels,
                        focalLengthMm = focalLengthMm.toInt(),
                        aperture = aperture,
                        subjectDistanceM = subjectDistanceM.toDouble(),
                        cocModeName = cocMode.name,
                        considerAiryDisk = considerAiryDisk,
                    )
                    presets.add(0, preset)
                    repository.savePreset(presets)
                    presetName = ""
                },
                onPresetSelected = { preset ->
                    val presetSensorIndex = sensorFormats.indexOfFirst { it.label == preset.sensorLabel }
                    if (presetSensorIndex >= 0) {
                        sensorIndex = presetSensorIndex
                    }
                    megapixelsInput = formatMegapixelsInput(preset.megapixels)
                    focalLengthMm = preset.focalLengthMm.toFloat()
                    apertureIndex = apertureStops.indexOfFirst { it == preset.aperture }.takeIf { it >= 0 } ?: 0
                    subjectDistanceM = preset.subjectDistanceM.toFloat()
                    cocModeName = preset.cocModeName
                    considerAiryDisk = preset.considerAiryDisk
                },
                onPresetDelete = { preset ->
                    presets.removeAll { it.id == preset.id }
                    repository.savePreset(presets)
                },
            )
        }
    }
}

@Composable
private fun SummaryCard(
    sensorFormat: SensorFormat,
    subjectDistanceM: Double,
    megapixels: Double?,
    result: DofResult?,
) {
    val subjectDistanceMm = subjectDistanceM * 1000.0
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "リアルタイム結果",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            MetricRow("センサー", sensorFormat.label)
            MetricRow("総画素数", megapixels?.let { "${formatNumber(it, 1)} MP" } ?: "数値を入力")
            MetricRow("許容錯乱円", result?.let { "${formatNumber(it.cocMm * 1000.0, 1)} µm" } ?: "-")
            MetricRow("過焦点距離", result?.let { formatDistance(it.hyperfocalMm) } ?: "-")
            MetricRow(
                "前側の深度",
                result?.let { formatDistance(it.frontDepthMm(subjectDistanceMm)) } ?: "-",
            )
            MetricRow(
                "後側の深度",
                result?.let {
                    it.backDepthMm(subjectDistanceMm)?.let(::formatDistance) ?: "∞"
                } ?: "-",
            )
            MetricRow(
                "総被写界深度",
                result?.let {
                    it.totalDepthMm?.let(::formatDistance) ?: "∞"
                } ?: "-",
            )
        }
    }
}

@Composable
private fun InputCard(
    sensorFormat: SensorFormat,
    detailsExpanded: Boolean,
    onDetailsExpandedChange: (Boolean) -> Unit,
    sensorMenuExpanded: Boolean,
    onSensorMenuExpandedChange: (Boolean) -> Unit,
    onSensorSelected: (Int) -> Unit,
    megapixelsInput: String,
    onMegapixelsChange: (String) -> Unit,
    cocMode: CocMode,
    onCocModeChange: (CocMode) -> Unit,
    considerAiryDisk: Boolean,
    onConsiderAiryDiskChange: (Boolean) -> Unit,
    focalLengthMm: Float,
    onFocalLengthChange: (Float) -> Unit,
    apertureIndex: Int,
    onApertureIndexChange: (Int) -> Unit,
    subjectDistanceM: Float,
    subjectDistanceMaxM: Float,
    onSubjectDistanceChange: (Float) -> Unit,
    megapixelsError: Boolean,
) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = "撮影条件",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            OutlinedButton(
                onClick = { onDetailsExpandedChange(!detailsExpanded) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (detailsExpanded) {
                        "詳細設定を閉じる"
                    } else {
                        "詳細設定を開く"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start,
                )
            }

            if (detailsExpanded) {
                Box {
                    OutlinedButton(
                        onClick = { onSensorMenuExpandedChange(true) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "センサーサイズ: ${sensorFormat.label}",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                        )
                    }
                    DropdownMenu(
                        expanded = sensorMenuExpanded,
                        onDismissRequest = { onSensorMenuExpandedChange(false) },
                        modifier = Modifier.fillMaxWidth(0.92f),
                    ) {
                        sensorFormats.forEachIndexed { index: Int, option: SensorFormat ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    onSensorSelected(index)
                                    onSensorMenuExpandedChange(false)
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = megapixelsInput,
                    onValueChange = onMegapixelsChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("総画素数 (MP)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = {
                        Text(
                            if (megapixelsError) "0.1 以上の数値を入力してください" else "例: 24.2"
                        )
                    },
                    isError = megapixelsError,
                    singleLine = true,
                )

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val buttonWidth = (maxWidth - 8.dp) / 2
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CocMode.values().forEach { option ->
                            OutlinedButton(
                                onClick = { onCocModeChange(option) },
                                modifier = Modifier.width(buttonWidth),
                            ) {
                                Text(
                                    text = if (option == cocMode) "● ${option.label}" else option.label,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val buttonWidth = (maxWidth - 8.dp) / 2
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onConsiderAiryDiskChange(false) },
                            modifier = Modifier.width(buttonWidth),
                        ) {
                            Text(
                                text = if (!considerAiryDisk) "● エアリー無視" else "エアリー無視",
                                textAlign = TextAlign.Center,
                            )
                        }
                        OutlinedButton(
                            onClick = { onConsiderAiryDiskChange(true) },
                            modifier = Modifier.width(buttonWidth),
                        ) {
                            Text(
                                text = if (considerAiryDisk) "● エアリー考慮" else "エアリー考慮",
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            SliderField(
                label = "焦点距離",
                valueLabel = "${focalLengthMm.toInt()} mm",
                value = focalLengthMm,
                valueRange = 1f..135f,
                steps = 133,
                onValueChange = { onFocalLengthChange(it.roundToIntValue()) },
            )

            SliderField(
                label = "F値",
                valueLabel = "F${formatAperture(apertureStops[apertureIndex])}",
                value = apertureIndex.toFloat(),
                valueRange = 0f..(apertureStops.lastIndex.toFloat()),
                steps = apertureStops.lastIndex - 1,
                onValueChange = { onApertureIndexChange(round(it).toInt().coerceIn(0, apertureStops.lastIndex)) },
            )

            SliderField(
                label = "被写体距離",
                valueLabel = "${formatNumber(subjectDistanceM.toDouble(), if (subjectDistanceM == subjectDistanceMaxM) 2 else 1)} m",
                value = subjectDistanceM,
                valueRange = 0.1f..subjectDistanceMaxM,
                steps = (((subjectDistanceMaxM - 0.1f) * 10f).toInt() - 1).coerceAtLeast(0),
                onValueChange = { onSubjectDistanceChange(it.snapToSubjectDistance(subjectDistanceMaxM)) },
            )
        }
    }
}

@Composable
private fun SliderField(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
private fun VisualizationCard(
    subjectDistanceM: Double,
    subjectDistanceMaxM: Float,
    onSubjectDistanceChange: (Double) -> Unit,
    onSubjectDragActiveChange: (Boolean) -> Unit,
    focalLengthMm: Double,
    result: DofResult,
) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "被写界深度イメージ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFFF4F0E8))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                    .padding(12.dp),
            ) {
                DofDiagram(
                    subjectDistanceM = subjectDistanceM,
                    subjectDistanceMaxM = subjectDistanceMaxM,
                    onSubjectDistanceChange = onSubjectDistanceChange,
                    onSubjectDragActiveChange = onSubjectDragActiveChange,
                    focalLengthMm = focalLengthMm,
                    result = result,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LegendItem("ピント位置", MaterialTheme.colorScheme.primary)
                LegendItem("被写界深度", Color(0xFF5E8B7E))
                LegendItem("過焦点距離", Color(0xFFC97C5D))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun DofDiagram(
    subjectDistanceM: Double,
    subjectDistanceMaxM: Float,
    onSubjectDistanceChange: (Double) -> Unit,
    onSubjectDragActiveChange: (Boolean) -> Unit,
    focalLengthMm: Double,
    result: DofResult,
) {
    val subjectDistanceMm = subjectDistanceM * 1000.0
    val isAtHyperfocal = result.farLimitMm == null
    val hyperfocalDisplayInfo = buildHyperfocalDisplayInfo(result, focalLengthMm)
    val displayFocusMm = if (isAtHyperfocal) result.hyperfocalMm else subjectDistanceMm
    val displayNearMm = result.nearLimitMm
    val displayFarMm = if (isAtHyperfocal) null else result.farLimitMm
    val farLimitMm = displayFarMm ?: (displayFocusMm * 1.8)
    val focusColor = MaterialTheme.colorScheme.primary
    val labelColor = Color(0xFF3A322D)
    val dofColor = Color(0xFF5E8B7E)
    val hyperfocalColor = Color(0xFFC97C5D)
    val graphMaxMm = (result.hyperfocalMm * 1.2).coerceAtLeast(100.0)
    var isDraggingSubject by remember { mutableStateOf(false) }
    val view = LocalView.current

    fun requestDisallowIntercept(disallow: Boolean) {
        var parent: ViewParent? = view.parent
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow)
            parent = parent.parent
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val leftPaddingPx = with(density) { 26.dp.toPx() }
        val rightPaddingPx = with(density) { 18.dp.toPx() }
        val usableWidthPx = (widthPx - leftPaddingPx - rightPaddingPx).coerceAtLeast(1f)
        val hitSlopPx = maxOf(with(density) { 32.dp.toPx() }, widthPx * 0.06f)

        fun mapX(distanceMm: Double): Float {
            val normalized = (distanceMm / graphMaxMm).toFloat()
            return (leftPaddingPx + (normalized * usableWidthPx))
                .coerceIn(leftPaddingPx, widthPx - rightPaddingPx)
        }

        fun xToDistance(x: Float): Float {
            val normalized = ((x - leftPaddingPx) / usableWidthPx).coerceIn(0f, 1f)
            val distanceM = ((graphMaxMm * normalized) / 1000.0).toFloat()
            return distanceM.snapToSubjectDistance(subjectDistanceMaxM)
        }

        val subjectX = mapX(displayFocusMm)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val leftPadding = 26.dp.toPx()
            val rightPadding = 18.dp.toPx()
            val topPadding = 28.dp.toPx()
            val bottomPadding = 28.dp.toPx()
            val baselineY = size.height - bottomPadding
            val usableWidth = size.width - leftPadding - rightPadding

            fun mapCanvasX(distanceMm: Double): Float {
                val normalized = (distanceMm / graphMaxMm).toFloat()
                return (leftPadding + (normalized * usableWidth))
                    .coerceIn(leftPadding, size.width - rightPadding)
            }

            val nearX = mapCanvasX(displayNearMm)
            val farX = mapCanvasX(minOf(farLimitMm, graphMaxMm))
            val hyperfocalX = mapCanvasX(minOf(result.hyperfocalMm, graphMaxMm))

            drawLine(
                color = Color(0xFF776B5D),
                start = Offset(leftPadding, baselineY),
                end = Offset(size.width - rightPadding, baselineY),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round,
            )

            drawRect(
                color = dofColor,
                topLeft = Offset(nearX, topPadding + 46.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(
                    width = (farX - nearX).coerceAtLeast(8.dp.toPx()),
                    height = baselineY - topPadding - 58.dp.toPx(),
                ),
                alpha = 0.42f,
            )

            drawLine(
                color = focusColor,
                start = Offset(subjectX, topPadding + 8.dp.toPx()),
                end = Offset(subjectX, baselineY),
                strokeWidth = 4.dp.toPx(),
            )

            drawLine(
                color = hyperfocalColor,
                start = Offset(hyperfocalX, topPadding + 8.dp.toPx()),
                end = Offset(hyperfocalX, baselineY),
                strokeWidth = 3.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                    floatArrayOf(18f, 12f)
                ),
            )

            drawIntoCanvas { canvas ->
                val textPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#3A322D")
                    textSize = 30f
                    isAntiAlias = true
                }
                canvas.nativeCanvas.drawText(
                    if (result.hyperfocalMm > graphMaxMm) "H > frame" else "H",
                    hyperfocalX - 14.dp.toPx(),
                    topPadding - 4.dp.toPx(),
                    textPaint,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInteropFilter { event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            val startedOnHandle =
                                kotlin.math.abs(event.x - subjectX) <= hitSlopPx
                            isDraggingSubject = startedOnHandle
                            if (startedOnHandle) {
                                onSubjectDragActiveChange(true)
                                requestDisallowIntercept(true)
                                onSubjectDistanceChange(xToDistance(event.x).toDouble())
                            }
                            startedOnHandle
                        }

                        MotionEvent.ACTION_MOVE -> {
                            if (!isDraggingSubject) {
                                false
                            } else {
                                onSubjectDragActiveChange(true)
                                requestDisallowIntercept(true)
                                onSubjectDistanceChange(xToDistance(event.x).toDouble())
                                true
                            }
                        }

                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> {
                            val wasDragging = isDraggingSubject
                            isDraggingSubject = false
                            onSubjectDragActiveChange(false)
                            requestDisallowIntercept(false)
                            wasDragging
                        }

                        else -> false
                    }
                }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text = "被写体距離 ${formatDistanceMeters(displayFocusMm)}",
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                DiagramDistanceLabel(
                    "Near",
                    if (isAtHyperfocal) hyperfocalDisplayInfo.nearText else formatDistanceMeters(displayNearMm),
                )
                DiagramDistanceLabel(
                    "Focus",
                    if (isAtHyperfocal) hyperfocalDisplayInfo.hyperfocalText else formatDistanceMeters(displayFocusMm),
                )
                DiagramDistanceLabel("Far", displayFarMm?.let(::formatDistanceMeters) ?: "∞")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                DiagramDistanceLabel(
                    "DoF",
                    buildDofRangeText(
                        frontDepthMm = (displayFocusMm - displayNearMm).coerceAtLeast(0.0),
                        backDepthMm = displayFarMm?.minus(displayFocusMm)?.coerceAtLeast(0.0),
                    ),
                )
                DiagramDistanceLabel(
                    "Hyperfocal",
                    "${hyperfocalDisplayInfo.nearText} / ${hyperfocalDisplayInfo.hyperfocalText}",
                )
            }
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DiagramDistanceLabel(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun buildDofRangeText(frontDepthMm: Double, backDepthMm: Double?): String {
    val front = formatDistance(frontDepthMm)
    val back = backDepthMm?.let(::formatDistance) ?: "∞"
    return "$front / $back"
}

private fun buildHyperfocalDisplayInfo(
    result: DofResult,
    focalLengthMm: Double,
): HyperfocalDisplayInfo {
    val frontDepthMm = DofCalculator.hyperfocalFrontDepthMm(
        hyperfocalMm = result.hyperfocalMm,
        focalLengthMm = focalLengthMm,
    )
    val roundedHyperfocalMm = roundMetersForDisplay(result.hyperfocalMm)
    val roundedFrontDepthMm = if (frontDepthMm >= 1000.0) {
        roundMetersForDisplay(frontDepthMm)
    } else {
        round(frontDepthMm).toDouble()
    }
    val nearDisplayMm = (roundedHyperfocalMm - roundedFrontDepthMm).coerceAtLeast(0.0)
    return HyperfocalDisplayInfo(
        nearText = if (nearDisplayMm >= 1000.0) {
            formatDistanceMeters(nearDisplayMm)
        } else {
            formatDistance(nearDisplayMm)
        },
        hyperfocalText = formatDistanceMeters(roundedHyperfocalMm),
    )
}

@Composable
private fun PresetCard(
    presetName: String,
    onPresetNameChange: (String) -> Unit,
    presets: List<DofPreset>,
    onSaveClick: () -> Unit,
    onPresetSelected: (DofPreset) -> Unit,
    onPresetDelete: (DofPreset) -> Unit,
) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "プリセット",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = presetName,
                onValueChange = onPresetNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("保存名 (任意)") },
                singleLine = true,
            )
            Button(
                onClick = onSaveClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("現在値を保存")
            }
            if (presets.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.take(5).forEach { preset ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPresetSelected(preset) },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.fillMaxWidth(0.78f)) {
                                Text(
                                    text = preset.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "${preset.sensorLabel} / ${preset.focalLengthMm}mm / F${formatAperture(preset.aperture)} / ${formatNumber(preset.subjectDistanceM, 1)}m",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onPresetDelete(preset) }) {
                                Text("削除")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
        )
    }
}

private fun formatDistance(distanceMm: Double): String {
    return if (distanceMm >= 1000.0) {
        "${formatNumber(distanceMm / 1000.0, 2)} m"
    } else {
        "${formatNumber(distanceMm, 0)} mm"
    }
}

private fun formatMegapixelsInput(megapixels: Double): String {
    val rounded = String.format("%.1f", megapixels)
    return if (rounded.endsWith(".0")) rounded.dropLast(2) else rounded
}

private fun formatNumber(value: Double, decimals: Int): String {
    return "%.${decimals}f".format(value)
}

private fun formatAperture(value: Double): String = formatNumber(value, 1)

private fun formatDistanceMeters(distanceMm: Double): String = "${formatNumber(distanceMm / 1000.0, 2)} m"

private fun roundMetersForDisplay(distanceMm: Double): Double = round(distanceMm / 10.0) * 10.0

private fun Float.roundToIntValue(): Float = round(this).toFloat()

private fun Float.roundToTenth(): Float = round(this * 10f) / 10f

private fun Float.snapToSubjectDistance(maxValue: Float): Float {
    val edgeThreshold = 0.051f
    return if (this >= maxValue - edgeThreshold) {
        maxValue
    } else {
        roundToTenth()
    }
}

@Preview(showBackground = true)
@Composable
private fun DofCalculatorPreview() {
    DoFTestTheme {
        DofCalculatorApp()
    }
}
