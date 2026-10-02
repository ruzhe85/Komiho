package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.waifu2x.AiUpscaleModel
import eu.kanade.tachiyomi.util.waifu2x.PluginUpscaleModel
import eu.kanade.tachiyomi.util.waifu2x.UpscaleModelRegistry
import eu.kanade.tachiyomi.util.waifu2x.UpscaleModelSpec
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * Komiho: the grouped image-enhancement picker, shared by **both** settings surfaces —
 * the in-reader sheet (via `ReadingModePage`) and Settings → Reader (via
 * `PreferenceItem.CustomPreference`). One implementation is what keeps the two structurally
 * identical; they had silently drifted apart before.
 *
 * Layout, top to bottom:
 *  - Off, on its own row;
 *  - **CPU** group — Lanczos3 / Catmull-Rom, plus the scale row while a CPU mode is active;
 *  - **GPU** group — one chip per built-in Vulkan model, plus the tile-size row while a GPU
 *    mode is active;
 *  - **NPU** group — models delivered by installed plugin APKs. Gated on
 *    `Waifu2x.isCdspAvailable` **first**: firmware-disabled compute DSPs (no fastrpc control
 *    node) skip the group entirely, since nothing there could ever run. 2026-09-19 模型插件化:
 *    the host APK ships **no** NPU contexts anymore, so with a CDSP present this group has
 *    three states:
 *      1. no QNN runtime (non-Qualcomm) — the whole group is not rendered;
 *      2. runtime present, no compatible plugin installed — a non-selectable hint that
 *         links to the model-package release on GitHub (installing a package and returning
 *         to this screen refreshes the list immediately);
 *      3. plugin installed — one chip per model whose generation set covers this device's
 *         on-chip HTP (a family that skips this generation stays invisible instead of
 *         offering a chip that could only ever fall back);
 *  - the enhancement-status overlay toggle.
 *
 * The groups are **purely visual**: the underlying state is still the single
 * [ReaderPreferences.enhancementMode] flag, so exactly one chip is selected at any time
 * (picking a model also sets mode 5). Callers draw the section heading themselves — this
 * composable renders contents only.
 */
@Composable
fun ImageEnhancementSection(
    preferences: ReaderPreferences,
    modifier: Modifier = Modifier,
) {
    val mode by preferences.enhancementMode.collectAsState()

    Column(modifier) {
        // Komiho: 降噪独立于增强档位（mode 0 也生效），常驻首行；打勾开关样式。
        val denoise by preferences.denoiseLevel.collectAsState()
        CheckboxItem(
            label = stringResource(MR.strings.enhancement_denoise),
            checked = denoise != 0,
            onClick = { preferences.denoiseLevel.set(if (denoise != 0) 0 else 1) },
        )

        // Komiho: 图像增强总开关（打勾样式）；关闭时下方 CPU/GPU/NPU 分组全部隐藏。
        val lastMode by preferences.enhancementLastMode.collectAsState()
        val setMode: (Int) -> Unit = {
            preferences.enhancementLastMode.set(it)
            preferences.enhancementMode.set(it)
        }
        CheckboxItem(
            label = stringResource(MR.strings.enhancement_group_title),
            checked = mode != 0,
            onClick = { setMode(if (mode != 0) 0 else if (lastMode != 0) lastMode else 2) },
        )

        if (mode != 0) {
        EnhancementGroupLabel(MR.strings.enhancement_group_cpu)
        SettingsChipRow {
            ReaderPreferences.CpuEnhancementModes.forEach { (flag, labelRes) ->
                FilterChip(
                    selected = mode == flag,
                    onClick = { setMode(flag) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
        if (mode in 2..3) {
            // Resampling scale applies to both CPU modes.
            val scale by preferences.lanczosScale.collectAsState()
            EnhancementParamLabel(MR.strings.enhancement_scale)
            SettingsChipRow {
                ReaderPreferences.LanczosScaleOptions.forEach { (value, labelRes) ->
                    FilterChip(
                        selected = scale == value,
                        onClick = { preferences.lanczosScale.set(value) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }
        }

        // findById() normalises unknown/removed stored ids (dropped model, uninstalled
        // plugin) to the default, so exactly one model chip stays selected no matter what
        // the preference holds.
        val modelId by preferences.aiModelId.collectAsState()
        val activeModel = UpscaleModelRegistry.findById(modelId)

        // GPU group — built-in Vulkan models only. NPU models live in their own group below.
        EnhancementGroupLabel(MR.strings.enhancement_group_gpu)
        SettingsChipRow {
            AiUpscaleModel.entries
                .filter { Waifu2x.isModelSupported(it) }
                .forEach { model ->
                    FilterChip(
                        selected = mode == 5 && activeModel == model,
                        onClick = {
                            preferences.aiModelId.set(model.id)
                            setMode(5)
                        },
                        label = { Text(model.displayLabel()) },
                    )
                }
        }
        if (mode == 5 && activeModel.backend == UpscaleModelSpec.Backend.NCNN_VULKAN) {
            // Tile edge only affects the GPU path (NPU uses the fixed QNN context).
            val tileSize by preferences.aiTileSize.collectAsState()
            EnhancementParamLabel(MR.strings.pref_ai_tile_size)
            SettingsChipRow {
                ReaderPreferences.AiTileSizeOptions.forEach { (value, labelRes) ->
                    FilterChip(
                        selected = tileSize == value,
                        onClick = { preferences.aiTileSize.set(value) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }
        }

        // NPU group — models from installed plugin APKs, only where a compute DSP *and* an
        // NPU runtime exist. The CDSP probe comes first: with the DSP switched off in firmware
        // there is nothing to load `libQnnHtp.so` for, and every model would only fall back to
        // Vulkan, which looks identical to a working NPU from the outside.
        if (Waifu2x.isCdspAvailable && Waifu2x.isQnnRuntimeAvailable) {
            EnhancementGroupLabel(MR.strings.enhancement_group_npu)

            val context = LocalContext.current
            var npuModels by remember { mutableStateOf(UpscaleModelRegistry.npuModels()) }

            // Refresh on first composition AND every time the screen comes back to the
            // foreground, so installing (or uninstalling) a model package and returning to
            // this screen updates the list immediately — no app restart, no preference
            // churn. The scan is a handful of PackageManager queries + one small JSON.
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        npuModels = UpscaleModelRegistry.refresh(context)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            LaunchedEffect(Unit) {
                npuModels = UpscaleModelRegistry.refresh(context)
            }

            val compatible = npuModels.filter { Waifu2x.detectedQnnArchitecture in it.qnnArches }
            if (compatible.isEmpty()) {
                // No plugin installed (or none covering this generation): a non-selectable
                // hint instead of an empty chip row.
                val hint = stringResource(MR.strings.ai_model_plugin_hint)
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable { context.openInBrowser(UpscaleModelRegistry.MODEL_PACKAGE_RELEASE_URL) }
                        .padding(
                            start = SettingsItemsPaddings.Horizontal,
                            end = SettingsItemsPaddings.Horizontal,
                            top = 4.dp,
                            bottom = 4.dp,
                        ),
                )
            } else {
                NpuModelPicker(
                    models = compatible,
                    activeModel = activeModel,
                    mode = mode,
                    onSelect = { model ->
                        preferences.aiModelId.set(model.id)
                        setMode(5)
                    },
                )
            }
        }

        // Komiho: 显示增强状态角标——随增强组开关隐藏。
        CheckboxItem(
            label = stringResource(MR.strings.pref_show_enhancement_status),
            pref = preferences.showEnhancementStatus,
        )

        if (mode == 5) {
            // Komiho: 大图强制 AI 增强（放开 r≤1 尺寸门控；MP 输出门仍兜底）。
            val bypass by preferences.aiBypassFitGate.collectAsState()
            CheckboxItem(
                label = stringResource(MR.strings.ai_bypass_fit),
                checked = bypass,
                onClick = { preferences.aiBypassFitGate.set(!bypass) },
            )
            // Komiho: AI 面积回缩（防摩尔纹）—— AI 2x 输出按显示尺寸用高斯核压回
            // 显示带通再交 SSIV（SSIV 整图双线性缩小无低通，高频网点会拍频）。
            val areaDownscale by preferences.aiAreaDownscale.collectAsState()
            CheckboxItem(
                label = stringResource(MR.strings.ai_area_downscale),
                checked = areaDownscale,
                onClick = { preferences.aiAreaDownscale.set(!areaDownscale) },
            )
            if (areaDownscale) {
                // Komiho: 回缩强度 —— σ 随档位走，盖住网点晶格周期才积得成均匀灰；
                // 强档灰度均匀但线稿更软，按网点粗细取舍。
                val strength by preferences.aiAreaDownscaleStrength.collectAsState()
                EnhancementParamLabel(MR.strings.ai_area_downscale_strength)
                SettingsChipRow {
                    ReaderPreferences.AiAreaDownscaleStrengthOptions.forEach { (value, labelRes) ->
                        FilterChip(
                            selected = strength == value,
                            onClick = { preferences.aiAreaDownscaleStrength.set(value) },
                            label = { Text(stringResource(labelRes)) },
                        )
                    }
                }
            }
        }
        }  // Komiho: if (mode != 0) —— 关闭时隐藏增强分组、模型选择、角标开关与强制增强
    }
}

/** Label text for either kind of model: moko resource for built-ins, JSON text for plugins. */
@Composable
private fun UpscaleModelSpec.displayLabel(): String =
    labelRes?.let { stringResource(it) } ?: labelText.orEmpty()

/**
 * Komiho (2026-10-01): the NPU picker, folded by **series**.
 *
 * The flat chip row stopped scaling as soon as a few model packages landed: nine chips, four
 * of them one family (`W2xEX Photo Small` / `Omni Small` / `Omni Turbo` / `Universal Fast`).
 * A series with more than one member collapses into a single header chip that also names what
 * is currently selected inside it (`W2xEX · Omni Small`); tapping the header expands its
 * members into a second row of chips.
 *
 * It behaves as an **accordion — only one series is ever open**: opening another closes the
 * previous one, tapping the open header closes it, and picking a standalone model closes
 * everything. Letting several stay open would drift back into the flat list this replaces.
 *
 * Models that stand alone — and any whose series cannot be derived — stay plain chips that
 * select on a **single tap**, which is the common case and must never cost an extra tap.
 *
 * The series comes from the manifest's optional `group` field, falling back to the label's
 * first word so packages built before that field existed still fold correctly.
 *
 * Selection itself is unchanged: still the one `aiModelId` preference, so exactly one chip is
 * highlighted across both rows.
 */
@Composable
private fun NpuModelPicker(
    models: List<PluginUpscaleModel>,
    activeModel: UpscaleModelSpec,
    mode: Int,
    onSelect: (PluginUpscaleModel) -> Unit,
) {
    // groupBy preserves encounter order, keeping chips in manifest order.
    val bySeries = models.groupBy { it.seriesName() }
    val foldable = bySeries.mapNotNull { (series, members) ->
        series?.takeIf { members.size > 1 }?.let { it to members }
    }
    // A null series (label with no space) and one-member series are never folded.
    val plain: List<PluginUpscaleModel> = buildList {
        bySeries.forEach { (series, members) ->
            if (series == null || members.size == 1) addAll(members)
        }
    }

    // Accordion: at most one series is open. Folding exists to keep this row short, so
    // letting several open at once would just rebuild the flat list it replaced. A nullable
    // String (not a set) matches that: "which series is open" has one answer at a time.
    var expandedSeries by rememberSaveable { mutableStateOf<String?>(null) }

    // The series holding the current selection opens by itself, so a collapsed header can
    // never hide what is actually running. This re-runs only when the selection moves into a
    // *different* series, so it cannot fight a manual collapse.
    val selectedSeries = models.firstOrNull { mode == 5 && it == activeModel }?.seriesName()
    LaunchedEffect(selectedSeries) {
        if (selectedSeries != null) {
            expandedSeries = selectedSeries
        }
    }

    SettingsChipRow {
        foldable.forEach { (series, members) ->
            val isExpanded = expandedSeries == series
            val selected = members.firstOrNull { mode == 5 && it == activeModel }
            FilterChip(
                selected = selected != null,
                onClick = {
                    // Opening a series closes whichever one was open; tapping the open one
                    // closes it, so the header doubles as the collapse control.
                    expandedSeries = if (isExpanded) null else series
                },
                label = {
                    Text(if (selected != null) "$series · ${selected.shortLabel(series)}" else series)
                },
                trailingIcon = {
                    Icon(
                        imageVector = if (isExpanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = null,
                    )
                },
            )
        }
        plain.forEach { model ->
            FilterChip(
                selected = mode == 5 && activeModel == model,
                onClick = {
                    onSelect(model)
                    // A standalone model belongs to no series, so nothing should stay open.
                    expandedSeries = null
                },
                label = { Text(model.displayLabel()) },
            )
        }
    }

    foldable.forEach { (series, members) ->
        if (expandedSeries == series) {
            SettingsChipRow {
                members.forEach { model ->
                    FilterChip(
                        selected = mode == 5 && activeModel == model,
                        onClick = { onSelect(model) },
                        label = { Text(model.shortLabel(series)) },
                    )
                }
            }
        }
    }
}

/**
 * Series name used to fold this model: the manifest's `group` when present, otherwise the
 * label's first word (`"W2xEX Omni Small"` -> `"W2xEX"`). A one-word label is a whole name
 * rather than a `series member` pair, so it yields null and the model stays a flat chip.
 */
private fun UpscaleModelSpec.seriesName(): String? {
    group?.takeIf { it.isNotBlank() }?.let { return it }
    val label = labelText?.trim().orEmpty()
    if (label.isEmpty()) return null
    val head = label.substringBefore(' ')
    return head.takeIf { head != label }
}

/** Member name with the series prefix stripped: `"W2xEX Omni Small"` minus `"W2xEX"`. */
private fun UpscaleModelSpec.shortLabel(series: String): String {
    val label = labelText.orEmpty()
    return label.removePrefix(series).trim().ifEmpty { label }
}

/** Muted heading for a platform group (CPU / GPU / NPU). */
@Composable
private fun EnhancementGroupLabel(labelRes: StringResource) {
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = SettingsItemsPaddings.Horizontal,
            end = SettingsItemsPaddings.Horizontal,
            top = SettingsItemsPaddings.Vertical,
            bottom = 2.dp,
        ),
    )
}

/** Lighter label for a parameter row nested inside a group (scale / tile size). */
@Composable
private fun EnhancementParamLabel(labelRes: StringResource) {
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = SettingsItemsPaddings.Horizontal,
            end = SettingsItemsPaddings.Horizontal,
            top = 4.dp,
        ),
    )
}
