package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.waifu2x.AiUpscaleModel
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
 *  - **GPU** group — one chip per Vulkan [AiUpscaleModel], plus the tile-size row while a GPU mode
 *    is active;
 *  - **NPU** group — one chip per Qualcomm QNN/HTP [AiUpscaleModel], shown only on devices with a
 *    usable NPU runtime;
 *  - the enhancement-status overlay toggle.
 *
 * The two groups are **purely visual**: the underlying state is still the single
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
        // Off — not part of either platform group.
        SettingsChipRow {
            FilterChip(
                selected = mode == 0,
                onClick = { preferences.enhancementMode.set(0) },
                label = { Text(stringResource(MR.strings.enhancement_off)) },
            )
        }

        EnhancementGroupLabel(MR.strings.enhancement_group_cpu)
        SettingsChipRow {
            ReaderPreferences.CpuEnhancementModes.forEach { (flag, labelRes) ->
                FilterChip(
                    selected = mode == flag,
                    onClick = { preferences.enhancementMode.set(flag) },
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

        // fromId() normalises unknown/removed stored ids to the default, so exactly one
        // model chip stays selected no matter what the preference holds.
        val modelId by preferences.aiModelId.collectAsState()
        val activeModel = AiUpscaleModel.fromId(modelId)

        // GPU group — Vulkan models only. NPU entries are pulled out into their own group below.
        EnhancementGroupLabel(MR.strings.enhancement_group_gpu)
        SettingsChipRow {
            AiUpscaleModel.entries
                .filter { it.backend == AiUpscaleModel.Backend.NCNN_VULKAN && Waifu2x.isModelSupported(it) }
                .forEach { model ->
                    FilterChip(
                        selected = mode == 5 && activeModel == model,
                        onClick = {
                            preferences.aiModelId.set(model.id)
                            preferences.enhancementMode.set(5)
                        },
                        label = { Text(stringResource(model.labelRes)) },
                    )
                }
        }
        if (mode == 5 && activeModel.backend == AiUpscaleModel.Backend.NCNN_VULKAN) {
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

        // NPU group — Qualcomm QNN/HTP context models, shown only where an NPU runtime exists.
        // Komiho: entries are filtered out on devices without a usable QNN runtime — same
        // "unsupported options stay invisible" contract as elsewhere.
        val npuModels = AiUpscaleModel.entries
            .filter { it.backend == AiUpscaleModel.Backend.QNN_HTP && Waifu2x.isModelSupported(it) }
        if (npuModels.isNotEmpty()) {
            EnhancementGroupLabel(MR.strings.enhancement_group_npu)
            SettingsChipRow {
                npuModels.forEach { model ->
                    FilterChip(
                        selected = mode == 5 && activeModel == model,
                        onClick = {
                            preferences.aiModelId.set(model.id)
                            preferences.enhancementMode.set(5)
                        },
                        label = { Text(stringResource(model.labelRes)) },
                    )
                }
            }
        }

        CheckboxItem(
            label = stringResource(MR.strings.pref_show_enhancement_status),
            pref = preferences.showEnhancementStatus,
        )
    }
}

/** Muted heading for a platform group (CPU / GPU). */
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
