package eu.kanade.tachiyomi.util.system

import eu.kanade.domain.ui.UiPreferences
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

    /** `ReaderPreferences.readerTheme` 的「白色」档位（0 白 / 1 黑 / 2 灰 / 3 自动）。 */
    private const val READER_THEME_WHITE = 0
}
