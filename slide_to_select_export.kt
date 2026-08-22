// ============================================================================
// Komiho V2 —— 划动多选（slide-to-select）相关代码导出
// 目的：长按进入选择模式，手指滑动连续选中划过的条目（series / book）。
// 文件：app/src/main/java/app/mihonsy/komga/ui/KomgaMainActivity.kt
//       app/src/main/java/app/mihonsy/komga/ui/ShelfComponents.kt
// BOM：androidx.compose.foundation 2026.06.01 (alpha)，Compose compiler 同版
// 当前现象：长按能进选择（anchor 选中正常），但手指滑动**不连续选中**划过的项。
// ============================================================================

// ---------------------------------------------------------------------------
// 【A】命中检测：顶层函数（两个文件各一对：list / grid）
//     入参 q3x9y = 指针 Y（已折算到「相对 LazyList 内容顶部」的坐标系）
// ---------------------------------------------------------------------------

// --- KomgaMainActivity.kt:152 ---
private fun resolveSeriesAtList(info: LazyListLayoutInfo, q3x9y: Float): String? {
    // 本 BOM 中 LazyListItemInfo.offset 是 Int（绝对 Y），size 是 Int（高度）
    val hit = info.visibleItemsInfo.firstOrNull {
        q3x9y >= it.offset.toFloat() && q3x9y < (it.offset + it.size).toFloat()
    }
    return hit?.key as? String
}

// --- KomgaMainActivity.kt:157 ---
private fun resolveSeriesAtGrid(info: LazyGridLayoutInfo, q3x9y: Float): String? {
    // 本 BOM 中 LazyGridItemInfo.offset 是 IntOffset，size 是 IntSize（用 .y/.height）
    val hit = info.visibleItemsInfo.firstOrNull {
        it.offset.y <= q3x9y && q3x9y < it.offset.y + it.size.height
    }
    return hit?.key as? String
}

// --- ShelfComponents.kt:82 ---
private fun resolveItemAtList(info: LazyListLayoutInfo, q3x9y: Float): String? {
    val hit = info.visibleItemsInfo.firstOrNull {
        q3x9y >= it.offset.toFloat() && q3x9y < (it.offset + it.size).toFloat()
    }
    return hit?.key as? String
}

// --- ShelfComponents.kt:87 ---
private fun resolveItemAtGrid(info: LazyGridLayoutInfo, q3x9y: Float): String? {
    val hit = info.visibleItemsInfo.firstOrNull {
        it.offset.y <= q3x9y && q3x9y < it.offset.y + it.size.height
    }
    return hit?.key as? String
}

// ---------------------------------------------------------------------------
// 【B】选择状态与拖拽状态（KomgaMainActivity.kt 内，Composable 作用域）
// ---------------------------------------------------------------------------
// --- KomgaMainActivity.kt:859-884 ---
var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
var showReadlistPicker by remember { mutableStateOf(false) }
var showCollectionPicker by remember { mutableStateOf(false) }

// 拖拽选择状态：dragActive=正在划动选择中；dragValue=本次划动要设成的目标值（选/不选）
var dragActive by remember { mutableStateOf(false) }
var dragValue by remember { mutableStateOf(true) }

val inSelection = selectedIds.isNotEmpty()
val selectedSeries = remember(selectedIds, series) { series.filter { it.id in selectedIds } }

val setSeriesSelect: (String, Boolean) -> Unit = { id, value ->
    selectedIds = if (value) selectedIds + id else selectedIds - id
}
val toggleSeriesSelect: (String) -> Unit = { id -> setSeriesSelect(id, id !in selectedIds) }
val startSeriesDragSelect: (String) -> Unit = { id ->
    // 长按起点：以该 item 当前是否选中决定本次划动是「全选」还是「全不选」
    val newValue = id !in selectedIds
    setSeriesSelect(id, newValue)
    dragActive = true
    dragValue = newValue
}
val onSeriesDragSelectAt: (String) -> Unit = { id ->
    // 划动经过的 item：只要 dragActive，就设成 dragValue
    if (dragActive) setSeriesSelect(id, dragValue)
}
val exitSeriesSelection: () -> Unit = { selectedIds = emptySet() }

// ShelfComponents.kt 中对应的 book 版本（同结构，变量名 series→item）：
//   startDragSelect / onDragSelectAt / setItemSelect / toggleItemSelect / exitItemSelection
//   逻辑与上面完全一致。

// ---------------------------------------------------------------------------
// 【C】手势挂载：detectDragGesturesAfterLongPress（4 处）
//     关键点：onDragStart.start 是相对 pointerInput 作用域(容器顶部)坐标；
//     早期版本用 onDrag 的 change.position.y（屏幕绝对坐标，含状态栏偏移）
//     导致坐标系不一致、划过命中错位。现改用 dragAmount 累加 currentDragY。
// ---------------------------------------------------------------------------

// --- KomgaMainActivity.kt 列表模式 (LazyColumn) ---
val listState = rememberLazyListState()
val padTop = with(LocalDensity.current) { 4.dp.toPx() }   // contentPadding.vertical = 4.dp
LazyColumn(
    state = listState,
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    modifier = shelfModifier
        .pointerInput(Unit) {
            var currentDragY = 0f
            detectDragGesturesAfterLongPress(
                onDragStart = { start: Offset ->
                    currentDragY = start.y                       // 相对容器作用域，OK
                    val pointerY = currentDragY - padTop
                    val id = resolveSeriesAtList(listState.layoutInfo, pointerY)
                    if (id != null) startSeriesDragSelect(id)
                },
                onDrag = { change: PointerInputChange, dragAmount: Offset ->
                    change.consume()
                    currentDragY += dragAmount.y                 // 用增量累加，绕开坐标歧义
                    val pointerY = currentDragY - padTop
                    val id = resolveSeriesAtList(listState.layoutInfo, pointerY)
                    if (id != null) onSeriesDragSelectAt(id)
                },
                onDragEnd = { dragActive = false },
                onDragCancel = { dragActive = false },
            )
        },
) {
    items(sortedSeries, key = { it.id }) { s ->
        LibrarySeriesListRow(
            client, s,
            onClick = { if (inSelection) toggleSeriesSelect(s.id) else onSeriesClick(s.id) },
            selected = s.id in selectedIds,
        )
    }
}

// --- KomgaMainActivity.kt 网格模式 (LazyVerticalGrid) ---
val gridState = rememberLazyGridState()
val padTop = with(LocalDensity.current) { 8.dp.toPx() }   // contentPadding.vertical = 8.dp
LazyVerticalGrid(
    state = gridState,
    columns = cells,
    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    modifier = shelfModifier
        .pointerInput(Unit) {
            var currentDragY = 0f
            detectDragGesturesAfterLongPress(
                onDragStart = { start: Offset ->
                    currentDragY = start.y
                    val pointerY = currentDragY - padTop
                    val id = resolveSeriesAtGrid(gridState.layoutInfo, pointerY)
                    if (id != null) startSeriesDragSelect(id)
                },
                onDrag = { change: PointerInputChange, dragAmount: Offset ->
                    change.consume()
                    currentDragY += dragAmount.y
                    val pointerY = currentDragY - padTop
                    val id = resolveSeriesAtGrid(gridState.layoutInfo, pointerY)
                    if (id != null) onSeriesDragSelectAt(id)
                },
                onDragEnd = { dragActive = false },
                onDragCancel = { dragActive = false },
            )
        },
) {
    items(sortedSeries, key = { it.id }) { s ->
        LibrarySeriesCard(client, s,
            onClick = { if (inSelection) toggleSeriesSelect(s.id) else onSeriesClick(s.id) },
            selected = s.id in selectedIds, titleInside = isCompact)
    }
}

// --- ShelfComponents.kt 列表模式 (book) ---
// 同 KomgaMainActivity 列表，区别：listState / padTop=4.dp / resolveItemAtList / startDragSelect / onDragSelectAt
// --- ShelfComponents.kt 网格模式 (book) ---
// 同 KomgaMainActivity 网格，区别：gridState / padTop=8.dp / resolveItemAtGrid / startDragSelect / onDragSelectAt

// ---------------------------------------------------------------------------
// 【D】item 行内已移除 onLongClick（消除手势冲突）
//     之前 item 上的 combinedClickable(onLongClick=...) 会吞掉长按手势，
//     导致 detectDragGesturesAfterLongPress 的 onDragStart 不触发、划不动。
//     移除后划动选择完全由容器 detectDragGesturesAfterLongPress 驱动。
//     row 组件的 onLongClick 形参保留默认 {}，不影响其他调用点。
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// 【E】待排查 / 怀疑点（给老师看的）
//   1. padTop 折算是否准确：pointer 坐标系原点在容器可绘制区顶部(含 contentPadding.top 之前)，
//      offset 是相对内容顶部。理论上 pointerY = change.y - padTop 能对上，但可能差一个常数
//     偏移（例如 list 实际顶部还有 toolbar/section header 等）。
//   2. dragAmount 累加是否真相对容器作用域：若 detectDragGesturesAfterLongPress 的
//      onDragStart.start 本身也是屏幕坐标（而非相对作用域），则 currentDragY 起点就偏了，
//      累加再多也整体错位。可加 Log 打印 start.y / dragAmount.y / 命中 id 验证。
//   3. LazyListItemInfo.offset 类型：本 BOM 实测为 Int（参照仓库 WheelPicker.kt:221 用
//      itemInfo.offset 当 Int 算术），但不同 BOM 版本可能变 IntOffset，需用 Log 确认。
//   4. 是否 onDrag 根本没触发：可在 onDrag 里打 Log 看是否被调用、dragAmount.y 是否有值。
// ---------------------------------------------------------------------------
