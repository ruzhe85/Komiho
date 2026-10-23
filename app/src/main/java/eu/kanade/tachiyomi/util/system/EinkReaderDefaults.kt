package eu.kanade.tachiyomi.util.system

import android.app.Application
import app.mihonsy.komga.data.KomgaPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import tachiyomi.core.common.preference.Preference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Komiho (2026-10-04): E-Ink 模式下阅读器「默认值」的幂等收敛器。
 *
 * 实机反馈：开了 E-Ink 还得手动去关「翻页动画」，阅读背景还是黑的。这两件事不是偏好，
 * 而是墨水屏的物理约束（连续重绘 = 残影 + 功耗；黑底在白场面板上也没意义），
 * 所以交给 E-Ink 开关直接调好，用户不必知道有这些开关。
 *
 * 但用户原来的选择不能丢，于是成对做**备份 + 还原**：
 *
 * | 项 | 何时收敛 | E-Ink 下的目标值 |
 * |---|---|---|
 * | 阅读器背景 `readerTheme` | [UiPreferences.isEinkModeActive] | 0 = 白 |
 * | 页模式翻页动画 `pageTransitionsPager` | [UiPreferences.isEinkAnimationOff] | 关 |
 * | 条漫翻页动画 v1 `pageTransitionsWebtoon` | 同上 | 关 |
 * | 条漫翻页动画 v2 `pageTransitionsWebtoonV2` | 同上 | 关 |
 * | 皮肤 `KomgaPreferences.appTheme` | [UiPreferences.isEinkModeActive] | `AppTheme.EINK_PURE` |
 * | 明暗模式 `KomgaPreferences.themeMode` | [UiPreferences.isEinkModeActive] | `LIGHT` |
 *
 * 备份存在（`isSet()`）即表示该项**当前正被 E-Ink 改写**：此时只保证目标值仍然成立
 * （用户在 E-Ink 期间又打开了翻页动画，下次 sync 会再关掉），**绝不更新备份**，
 * 因此「原值」永远是开启 E-Ink 之前的那份。条件不再成立时写回原值并清除备份。
 *
 * 幂等：可以随便调（App 启动自愈、开关切换即时生效都靠它）。全程 `runCatching`，
 * 失败只是维持现状，绝不让启动/设置界面崩掉。
 */
object EinkReaderDefaults {

    /**
     * 把阅读器的四项按当前 E-Ink 状态收敛或还原。调用点：
     * `App.onCreate`（老用户首启自愈）、外观设置里的 E-Ink 主开关与「关闭全局动画」子开关。
     */
    fun sync() = runCatching {
        val ui = Injekt.get<UiPreferences>()
        val reader = Injekt.get<ReaderPreferences>()

        // 1) 阅读器背景：E-Ink 生效 → 白底；E-Ink 关闭 → 还原用户原背景。
        restoreOrApply(
            backup = ui.einkBackupReaderTheme,
            target = reader.readerTheme,
            active = ui.isEinkModeActive,
            einkValue = READER_THEME_WHITE,
        )

        // 2) 翻页动画（页 / 条漫 v1 / 条漫 v2）：E-Ink 关动画 → 全关；否则各自还原。
        //    与 EinkMotion.isAnimationOff 口径一致 —— 用户单独关掉「关闭全局动画」时，
        //    这几项控制权交还用户（还原原值），而不是留着一个「改了也没用」的状态。
        val animationOff = ui.isEinkAnimationOff
        restoreOrApply(
            backup = ui.einkBackupPageTransitionsPager,
            target = reader.pageTransitionsPager,
            active = animationOff,
            einkValue = false,
        )
        restoreOrApply(
            backup = ui.einkBackupPageTransitionsWebtoon,
            target = reader.pageTransitionsWebtoon,
            active = animationOff,
            einkValue = false,
        )
        restoreOrApply(
            backup = ui.einkBackupPageTransitionsWebtoonV2,
            target = reader.pageTransitionsWebtoonV2,
            active = animationOff,
            einkValue = false,
        )

        // 3) 皮肤（Komga 侧）：E-Ink 生效 → 切 EINK；关闭 → 还原原皮肤 / 回落默认皮肤。
        syncKomgaAppTheme(ui)

        // 4) 明暗模式（Komga 侧）：E-Ink 生效 → 锁定浅色；关闭 → 还原原值。
        syncThemeMode(ui)
    }

    /**
     * Komga 侧皮肤的收敛（E-Ink 开关只负责它自己写过的那一份）。
     *
     * 为什么是这一份：`KomihoTheme` 全局配色读的是 `KomgaPreferences.appTheme`，而全仓只有两处
     * 写它 —— 设置页的皮肤列表与 E-Ink 开关（都在 `KomgaMainActivity`）。Mihon 侧
     * `UiPreferences.appTheme` 是另一份存储（驱动 Activity 的 XML 主题），开关从未写过，故不代管。
     *
     * **迁移护栏**：本修复前就已开着 E-Ink 的用户，其皮肤就是 `EINK`。若无条件快照，关闭后会
     * 「还原」成 EINK —— bug 换个形式复现。所以只在当前值**不是 e-ink 皮肤**（`EINK` /
     * `EINK_PURE`）时才记快照；这也顺带让「模式已关、皮肤仍是 `EINK`」这一存量坏状态在
     * 下一次 sync（App 启动即触发）时自愈。
     *
     * 幂等且可重复调用；异常由 [sync] 的 runCatching 兜住（皮肤读写失败只维持现状）。
     */
    private fun syncKomgaAppTheme(ui: UiPreferences) {
        val komga = KomgaPreferences(Injekt.get<Application>())
        val current = komga.appTheme
        // Komiho: E-Ink 皮肤有两套（原始档位版 EINK 与纯色版 EINK_PURE），都由用户自选，
        // 开关不再把已在 e-ink 皮肤上的用户改写成另一套。开关的默认值是纯色版 ——
        // 页面内没有中间灰，最贴合墨水屏。
        val defaultSkin = AppTheme.EINK_PURE.name
        val alreadyEinkSkin = current == AppTheme.EINK.name || current == defaultSkin
        if (ui.isEinkModeActive) {
            if (!ui.einkBackupAppTheme.isSet() && !alreadyEinkSkin) {
                ui.einkBackupAppTheme.set(current)
            }
            if (!alreadyEinkSkin) {
                komga.appTheme = defaultSkin
            }
        } else if (ui.einkBackupAppTheme.isSet()) {
            komga.appTheme = ui.einkBackupAppTheme.get()
            ui.einkBackupAppTheme.delete()
        } else if (current == AppTheme.EINK.name) {
            // 存量坏状态：升级前就开着 E-Ink、因此没有快照 → 回落到默认皮肤。
            // 只判 EINK 不判 EINK_PURE：后者是用户不开模式也能自己选的主题，
            // 一并回落会把「只是喜欢这套皮肤」的用户强行改掉。
            komga.appTheme = AppTheme.DEFAULT.name
        }
    }

    /**
     * Komga 侧明暗模式的收敛（与 [syncKomgaAppTheme] 同一套口径）。
     *
     * E-Ink 生效时锁 LIGHT：反射式屏靠环境光反射，白底最清楚，深色反而像脏灰。
     * 备份存在即表示当前正被 E-Ink 改写，此时只保证目标值仍成立、不覆盖快照；
     * 模式关闭后写回快照并清除。
     *
     * 模式已关但值仍是 LIGHT 且无快照的情况不处理：无快照时无法区分「用户自己选的
     * 浅色」与「E-Ink 留下的浅色」，强改会误伤用户选择。
     */
    private fun syncThemeMode(ui: UiPreferences) {
        val komga = KomgaPreferences(Injekt.get<Application>())
        val current = komga.themeMode
        val lightName = ThemeMode.LIGHT.name
        if (ui.isEinkModeActive) {
            if (!ui.einkBackupThemeMode.isSet() && current != lightName) {
                ui.einkBackupThemeMode.set(current)
            }
            if (current != lightName) {
                komga.themeMode = lightName
            }
        } else if (ui.einkBackupThemeMode.isSet()) {
            komga.themeMode = ui.einkBackupThemeMode.get()
            ui.einkBackupThemeMode.delete()
        }
    }

    /**
     * 备份 / 收敛 / 还原三合一。
     *
     * - [active]：当前是否处于「E-Ink 应改写这一项」的状态；
     * - 成立且未备份 → 先快照原值，再写 [einkValue]；已备份 → 只补写 [einkValue]（不覆盖快照）；
     * - 不成立且已备份 → 写回快照并清除备份；不成立且无备份 → 什么都不做（用户从未开过 E-Ink）。
     */
    private fun <T> restoreOrApply(
        backup: Preference<T>,
        target: Preference<T>,
        active: Boolean,
        einkValue: T,
    ) {
        if (active) {
            if (!backup.isSet()) {
                backup.set(target.get())
            }
            if (target.get() != einkValue) {
                target.set(einkValue)
            }
        } else if (backup.isSet()) {
            target.set(backup.get())
            backup.delete()
        }
    }

    /** `ReaderPreferences.readerTheme` 的「白色」档位（0 白 / 1 黑 / 2 灰；3 自动已隐藏，存量已收敛到 1）。 */
    private const val READER_THEME_WHITE = 0
}
