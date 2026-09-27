#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""结构自检：阅读器进度气泡缩略图 相关改动（无 SDK，靠静态校验替代编译）。"""
import os
import re
import sys

ROOT = r"E:/code/komiho"

FILES = {
    "ReaderAppBars": os.path.join(ROOT, "app/src/main/java/eu/kanade/presentation/reader/appbars/ReaderAppBars.kt"),
    "ReaderActivity": os.path.join(ROOT, "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt"),
    "ChapterNavigator": os.path.join(ROOT, "app/src/main/java/eu/kanade/presentation/reader/components/ChapterNavigator.kt"),
    "KomgaPreferences": os.path.join(ROOT, "komga-data/src/main/java/app/mihonsy/komga/data/KomgaPreferences.kt"),
    "KomgaApiClient": os.path.join(ROOT, "komga-data/src/main/java/app/mihonsy/komga/data/KomgaApiClient.kt"),
    "ReaderPageThumbnailFetcher": os.path.join(ROOT, "app/src/main/java/eu/kanade/tachiyomi/data/coil/ReaderPageThumbnailFetcher.kt"),
    "App": os.path.join(ROOT, "app/src/main/java/eu/kanade/tachiyomi/App.kt"),
}

STRINGS = {
    "default": os.path.join(ROOT, "app/src/main/res/values/strings.xml"),
    "en": os.path.join(ROOT, "app/src/main/res/values-en/strings.xml"),
    "zh-rTW": os.path.join(ROOT, "app/src/main/res/values-zh-rTW/strings.xml"),
}

fails = []


def strip_code(src):
    """剥离注释与字符串字面量后返回代码文本（不完美但够用）。"""
    out = []
    i, n = 0, len(src)
    in_line = in_block = False
    in_str = None  # None / '"' / "'"
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if in_line:
            if c == "\n":
                in_line = False
                out.append(" ")
            i += 1
            continue
        if in_block:
            if c == "*" and nxt == "/":
                in_block = False
                i += 2
                out.append(" ")
            else:
                i += 1
            continue
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == in_str:
                in_str = None
            i += 1
            continue
        # not in comment/string
        if c == "/" and nxt == "/":
            in_line = True
            i += 2
            continue
        if c == "/" and nxt == "*":
            in_block = True
            i += 2
            continue
        if c == '"' or c == "'":
            in_str = c
            i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def brackets_ok(path):
    src = open(path, encoding="utf-8", errors="replace").read()
    s = strip_code(src)
    # 只用原始计数（Kotlin 字符串模板 {} 成对，不干扰；原始字符串 """ 罕见）
    if s.count("{") != s.count("}"):
        return False, (s.count("{"), s.count("}"))
    if s.count("(") != s.count(")"):
        return False, (s.count("("), s.count(")"))
    return True, None


def assert_in(path, needle, label):
    src = open(path, encoding="utf-8", errors="replace").read()
    if needle not in src:
        fails.append(f"[缺失] {os.path.basename(path)}: 找不到 {label} ({needle!r})")
    else:
        print(f"  [OK] {os.path.basename(path)}: {label}")


def assert_not_in(path, needle, label):
    src = open(path, encoding="utf-8", errors="replace").read()
    if needle in src:
        fails.append(f"[残留] {os.path.basename(path)}: 仍存在 {label} ({needle!r})")
    else:
        print(f"  [OK] {os.path.basename(path)}: {label}")


# 1) 括号平衡
print("== 括号平衡 ==")
for name, path in FILES.items():
    ok, info = brackets_ok(path)
    if ok:
        print(f"  [OK] {name}: {{}} / () 平衡")
    else:
        fails.append(f"[括号失衡] {name}: {info}")

# 2) 参数穿透：ChapterNavigator 签名 + 两处调用 + ReaderActivity 调用
print("== 参数穿透 thumbnailModelForPage ==")
assert_in(FILES["ChapterNavigator"], "thumbnailModelForPage: ((Int) -> Any?)? = null", "ChapterNavigator 参数声明")
assert_in(FILES["ChapterNavigator"], "modelForPage = thumbnailModelForPage", "ChapterNavigator 把 provider 传给 PagePreviewStrip")
assert_in(FILES["ChapterNavigator"], "private fun PagePreviewStrip(", "PagePreviewStrip 组合项声明")
assert_in(FILES["ChapterNavigator"], "thumbnailModelForPage != null && totalPages > 1", "常驻显示判定（不依赖 previewPage）")
assert_in(FILES["ChapterNavigator"], "val activePage = if (previewPage >= 0) previewPage else currentPage", "拖动/非拖动都显示（常驻）")
assert_in(FILES["ReaderAppBars"], "thumbnailModelForPage: ((Int) -> Any?)? = null", "ReaderAppBars 参数声明")
# 两处 ChapterNavigator 调用都应传该参数
ra = open(FILES["ReaderAppBars"], encoding="utf-8", errors="replace").read()
cnt = ra.count("thumbnailModelForPage = thumbnailModelForPage")
if cnt >= 2:
    print(f"  [OK] ReaderAppBars: 两处 ChapterNavigator 均传参 (count={cnt})")
else:
    fails.append(f"[缺失] ReaderAppBars: 只找到 {cnt} 处 thumbnailModelForPage 传参（期望>=2）")
assert_in(FILES["ReaderActivity"], "thumbnailModelForPage = thumbnailModelForPage", "ReaderActivity 传参给 ReaderAppBars")

# 3) MangaCover 构造（带 Komga 鉴权）在 ReaderActivity lambda 内
print("== MangaCover 提供器 ==")
ra = open(FILES["ReaderActivity"], encoding="utf-8", errors="replace").read()
if "MangaCover(" in ra and "KomgaSource.ID" in ra and "client.pageThumbnailUrl" in ra:
    print("  [OK] ReaderActivity: MangaCover + KomgaSource.ID + pageThumbnailUrl 齐备")
else:
    fails.append("[缺失] ReaderActivity: MangaCover 构造不完整")

# 4) 偏好与 key 常量
print("== KomgaPreferences 开关 ==")
assert_in(FILES["KomgaPreferences"], "readerProgressBubbleEnabled", "偏好属性 readerProgressBubbleEnabled")
assert_in(FILES["KomgaPreferences"], "KEY_READER_PROGRESS_BUBBLE_ENABLED", "key 常量 KEY_READER_PROGRESS_BUBBLE_ENABLED")

# 5) API client 缩图 URL
print("== KomgaApiClient 缩图 URL ==")
assert_in(FILES["KomgaApiClient"], "fun pageThumbnailUrl(", "pageThumbnailUrl 方法")

# 6) 字符串资源三套
print("== 字符串资源 ==")
for key in ("reader_progress_bubble", "settings_advanced"):
    for lang, path in STRINGS.items():
        if not os.path.exists(path):
            fails.append(f"[缺失] strings({lang}): 文件不存在 {path}")
            continue
        s = open(path, encoding="utf-8", errors="replace").read()
        if f'name="{key}"' not in s:
            fails.append(f"[缺失] strings({lang}): <string name=\"{key}\"> 未定义")
        else:
            print(f"  [OK] strings({lang}): {key}")

# 7) SettingsTab 高级子页入口 + 子页内开关（在 KomgaMainActivity.kt）
print("== SettingsTab 高级子页 + 开关 ==")
km = os.path.join(ROOT, "app/src/main/java/app/mihonsy/komga/ui/KomgaMainActivity.kt")
assert_in(km, "var showAdvanced by remember", "showAdvanced 子页状态")
assert_in(km, "Icons.Filled.Tune", "高级入口图标 Tune")
assert_in(km, "onPreferenceClick = { showAdvanced = true }", "高级行点击进入子页")
assert_in(km, "settings_advanced", "SettingsTab 引用 settings_advanced")
assert_in(km, "reader_progress_bubble", "SettingsTab 引用开关字符串")
assert_in(km, "prefs.readerProgressBubbleEnabled", "SettingsTab 绑定开关状态")
assert_in(km, "bubbleEnabled = it", "开关本地镜像状态即时刷新")
assert_in(km, "SwitchPreferenceWidget(", "高级子页用 SwitchPreferenceWidget")

# 8) thumbnailCount 穿透：ChapterNavigator 签名 + ReaderAppBars 签名 + 两处调用 + ReaderActivity 传参 + PagePreviewStrip count
print("== thumbnailCount 穿透 ==")
assert_in(FILES["ChapterNavigator"], "thumbnailCount: Int = 0", "ChapterNavigator 参数声明")
assert_in(FILES["ChapterNavigator"], "count: Int,", "PagePreviewStrip 接收 count 参数")
assert_in(FILES["ReaderAppBars"], "thumbnailCount: Int = 0", "ReaderAppBars 参数声明")
ra = open(FILES["ReaderAppBars"], encoding="utf-8", errors="replace").read()
cnt = ra.count("thumbnailCount = thumbnailCount")
if cnt >= 2:
    print(f"  [OK] ReaderAppBars: 两处 ChapterNavigator 均传 thumbnailCount (count={cnt})")
else:
    fails.append(f"[缺失] ReaderAppBars: 只找到 {cnt} 处 thumbnailCount 传参（期望>=2）")
assert_in(FILES["ReaderActivity"], "thumbnailCount = thumbnailCount", "ReaderActivity 传 thumbnailCount 给 ReaderAppBars")
assert_in(FILES["ReaderActivity"], "!bubbleEnabled", "开关关闭时整体关闭")
assert_in(FILES["ReaderActivity"], "prefs.readerProgressBubbleEnabled", "读取开关偏好")
ra = open(FILES["ReaderActivity"], encoding="utf-8", errors="replace").read()
if "ORIENTATION_LANDSCAPE" in ra and "if (isLandscape) 4 else 3" in ra:
    print("  [OK] ReaderActivity: 横屏 4 张 / 竖屏 3 张（按方向固定）")
else:
    fails.append("[缺失] ReaderActivity: 未实现横屏4/竖屏3 的固定数量")

# 9) 本地 / SMB / WebDAV / 远程 缩图：复用 PageLoader（ReaderPageThumbnailFetcher）
print("== 非 Komga 来源缩图（ReaderPageThumbnailFetcher） ==")
assert_in(FILES["ReaderPageThumbnailFetcher"], "class ReaderPageThumbnailFetcher(", "Fetcher 类声明")
assert_in(FILES["ReaderPageThumbnailFetcher"], "data class ReaderPageThumbnailRequest(", "缩图请求数据类")
assert_in(FILES["ReaderPageThumbnailFetcher"], "Fetcher.Factory<ReaderPageThumbnailRequest>", "Fetcher.Factory 注册类型")
assert_in(FILES["ReaderPageThumbnailFetcher"], "pageLoader.loadPage(readerPage)", "复用 PageLoader 加载页")
assert_in(FILES["ReaderPageThumbnailFetcher"], "readerPage.statusFlow.first", "用 statusFlow 等终态（避免 loadPage 对已就绪页挂起）")
assert_in(FILES["ReaderPageThumbnailFetcher"], "readerPage.stream?.invoke()", "取独立流交给 Coil（不消耗 reader 渲染流）")
assert_in(FILES["App"], "add(ReaderPageThumbnailFetcher.Factory())", "Coil 注册 ReaderPageThumbnailFetcher")
assert_in(FILES["App"], "import eu.kanade.tachiyomi.data.coil.ReaderPageThumbnailFetcher", "App.kt 导入 ReaderPageThumbnailFetcher（防 Unresolved reference）")
ra = open(FILES["ReaderActivity"], encoding="utf-8", errors="replace").read()
if "ReaderPageThumbnailRequest(" in ra and "val pageLoader = state.currentChapter?.pageLoader" in ra and "val pages = state.currentChapter?.pages" in ra:
    print("  [OK] ReaderActivity: 非 Komga 来源构造 ReaderPageThumbnailRequest（复用 PageLoader + pages）")
else:
    fails.append("[缺失] ReaderActivity: 非 Komga 分支未构造 ReaderPageThumbnailRequest")

# 10) A 方案：子采样解码 + 进程内 LRU（避免整图解码 / 重复拖动重解）
print("== A方案: 子采样解码 + LRU ==")
rf = FILES["ReaderPageThumbnailFetcher"]
assert_in(rf, "decodeSampled(", "子采样解码私有方法")
assert_in(rf, "inJustDecodeBounds", "先探边界算 inSampleSize")
assert_in(rf, "inSampleSize = sample", "按采样比解码（不先解整图）")
assert_in(rf, "object ThumbnailCache", "进程内缩图 LRU 单例")
assert_in(rf, "LruCache<String, ByteArray>", "LRU 类型（key=章节+页码, value=压缩字节）")
assert_in(rf, "ThumbnailCache[cacheKey]", "fetch 先查 LRU 命中")
assert_in(rf, "ThumbnailCache.put(cacheKey, bytes)", "生成后回写 LRU")
assert_in(rf, "val raw = input.use { it.readBytes() }", "整段读入一次避免 SMB/WebDAV 双次拉网")
assert_in(rf, "DataSource.MEMORY", "标注 MEMORY 避免 Coil 落盘缓存")

# 11) 预览模式新行为：默认不出图 / 按压才展开 / 拖动不跳转 / 点击缩略图提交 / 拖动不生成图
print("== 预览模式行为（默认不出图、点击出图、拖动不跳转） ==")
cn = FILES["ChapterNavigator"]
src_cn = open(cn, encoding="utf-8", errors="replace").read()

assert_in(cn, "val previewSupported = thumbnailModelForPage != null && totalPages > 1 && thumbnailCount > 0", "预览支持标记 previewSupported")
assert_in(cn, "var previewOpen by remember { mutableStateOf(false) }", "预览默认关闭（不出图）")
assert_in(cn, "PressInteraction.Press", "按压进度条才展开预览")
assert_in(cn, "interactionSource.interactions.collect", "订阅按压交互流")
# 滑块必须跟随手指：①预览时跟随 previewPage（预览模式不跳转 ⇒ currentPage 不变，
# 若跟随 currentPage 或直接不赋值都会导致「拖动不跟随」）②该赋值必须位于 previewPage 声明之后
assert_in(
    cn,
    "state.value = (if (previewPage >= 0) previewPage else currentPage).toFloat()",
    "滑块位置跟随预览目标（否则拖动不跟随）",
)
assert_not_in(cn, "state.value = currentPage.toFloat()", "裸跟随 currentPage 的旧写法已移除")
i_decl = src_cn.find("var previewPage by remember")
i_sync = src_cn.find("state.value = (if (previewPage")
if i_decl >= 0 and i_sync > i_decl:
    print("  [OK] ChapterNavigator.kt: 滑块同步语句位于 previewPage 声明之后（无前向引用）")
else:
    fails.append(f"[缺失] ChapterNavigator.kt: 滑块同步语句位置错误 (decl={i_decl}, sync={i_sync})")

# 「拖动中零加载 / 停下来才跳转」对原生模式同样生效
m_fin = re.search(r"state\.onValueChangeFinished = \{(.*?)\n    \}\n", src_cn, re.S)
if m_fin and "commitJump(" in m_fin.group(1) and "!previewSupported" in m_fin.group(1):
    print("  [OK] ChapterNavigator.kt: 松手才提交跳转（原生模式走 commitJump）")
else:
    fails.append("[缺失] ChapterNavigator.kt: onValueChangeFinished 未在原生模式下提交跳转")
assert_in(cn, "val commitJump: (Int) -> Unit = {", "统一提交入口 commitJump")
m_jump = re.search(r"val commitJump[^\n]*\n(.*?)\n    \}\n", src_cn, re.S)
if m_jump:
    body3 = m_jump.group(1)
    if "previewPage = -1" in body3 and "onPageIndexChange(page)" in body3 and "if (page == currentPage)" in body3:
        print("  [OK] ChapterNavigator.kt: commitJump 对「目标=当前页」不跳转加载")
    else:
        fails.append("[缺失] ChapterNavigator.kt: commitJump 未处理「目标=当前页」分支")
else:
    fails.append("[缺失] ChapterNavigator.kt: 未找到 commitJump 定义")
i_jumpdecl = src_cn.find("val commitJump")
i_finished = src_cn.find("state.onValueChangeFinished = {")
if 0 <= i_jumpdecl < i_finished:
    print("  [OK] ChapterNavigator.kt: commitJump 声明在 onValueChangeFinished 之前（无前向引用）")
else:
    fails.append("[缺失] ChapterNavigator.kt: commitJump 声明位置错误（前向引用）")
# isScrollingThroughPages 复位必须延后到页面真正到位
m_effs = re.findall(r"LaunchedEffect\(currentPage\) \{\n(.*?)\n    \}\n", src_cn, re.S)
if any("onPageIndexChangeFinished()" in b for b in m_effs):
    print("  [OK] ChapterNavigator.kt: 页面到位后才复位 isScrollingThroughPages（避免菜单被立即收起）")
else:
    fails.append(f"[缺失] ChapterNavigator.kt: 收尾 effect 未复位 isScrollingThroughPages（找到 {len(m_effs)} 个 effect）")
assert_not_in(cn, "if (thumbnailModelForPage != null && totalPages > 1 && thumbnailCount > 0)", "旧的常驻显示条件已移除")

# 预览模式下拖动不得跳转：onValueChange 里的 onPageIndexChange 必须整体被 !previewSupported 守卫
m = re.search(r"state\.onValueChange = \{(.*?)state\.onValueChangeFinished", src_cn, re.S)
if m:
    body = re.sub(
        r"if \(!previewSupported\) \{[^}]*onPageIndexChange\([^)]*\)[^}]*\}",
        "",
        m.group(1),
        flags=re.S,
    )
    if "onPageIndexChange(" not in body:
        print("  [OK] ChapterNavigator.kt: 预览模式下拖动不跳转（onPageIndexChange 受守卫）")
    else:
        fails.append("[缺失] ChapterNavigator.kt: onValueChange 仍无条件调用 onPageIndexChange")
else:
    fails.append("[缺失] ChapterNavigator.kt: 未找到 state.onValueChange 赋值块")

# 两处预览条都必须由 previewOpen 门控
n_gate = src_cn.count("previewSupported && previewOpen")
if n_gate >= 2:
    print(f"  [OK] ChapterNavigator.kt: 两处预览条均由 previewOpen 门控 (count={n_gate})")
else:
    fails.append(f"[缺失] ChapterNavigator.kt: 预览条未被 previewOpen 门控（仅 {n_gate} 处）")

# 点击缩略图 → 关闭预览 + 提交跳转（统一走 commitJump）
n_click = src_cn.count("onPageClick = commitJump")
if n_click >= 2:
    print(f"  [OK] ChapterNavigator.kt: 两处预览条均把点击接到 commitJump (count={n_click})")
else:
    fails.append(f"[缺失] ChapterNavigator.kt: 预览条点击未接 commitJump（仅 {n_click} 处）")

# 拖动中不生成缩略图（占位），松手后才加载
assert_in(cn, "loadImages: Boolean,", "PagePreviewStrip 参数 loadImages")
assert_in(cn, "onPageClick: (Int) -> Unit,", "PagePreviewStrip 参数 onPageClick")
n_load = src_cn.count("loadImages = !sliderDragged")
if n_load >= 2:
    print(f"  [OK] ChapterNavigator.kt: 两处传入 loadImages = !sliderDragged (count={n_load})")
else:
    fails.append(f"[缺失] ChapterNavigator.kt: loadImages 传参不足（{n_load} 处）")
assert_in(cn, "if (loadImages) {", "拖动中走占位分支（不发起任何 Coil 请求）")
assert_in(cn, "Box(modifier = Modifier.size(itemSize))", "拖动中的同尺寸占位框")
assert_in(cn, "Modifier.clickable { onPageClick(p) }", "缩略图可点击")
assert_in(cn, "import kotlinx.coroutines.flow.collect", "导入 Flow.collect（按压交互流）")
assert_in(cn, "import androidx.compose.foundation.interaction.PressInteraction", "导入 PressInteraction")
assert_in(cn, "import androidx.compose.runtime.mutableStateOf", "导入 mutableStateOf")

print()
if fails:
    print(f"❌ 自检失败，{len(fails)} 项：")
    for f in fails:
        print("  - " + f)
    sys.exit(1)
else:
    print("✅ 全部结构自检通过")
