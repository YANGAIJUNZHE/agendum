package com.agendum.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agendum.app.ui.ClientPage
import com.agendum.app.ui.theme.LocalAgendumColors
import com.agendum.app.ui.theme.LocalReducedMotion
import kotlin.math.roundToInt

/** 青岚磁吸底栏的纯数学几何快照，便于单测锁住零滞后、零遮挡与零溢出的契约。 */
@Immutable
data class MagneticDockGeometry(
    val dockWidthPx: Int,
    val innerTrackWidthPx: Int,
    val addSlotWidthPx: Int,
    val addGapWidthPx: Int,
    val addScale: Float,
    val addRotationDeg: Float,
    val addAlpha: Float,
    val tabXsPx: List<Int>,
    val tabWidthsPx: List<Int>,
    val labelReveals: List<Float>,
    val pillXPx: Int,
    val pillWidthPx: Int,
)

const val MAGNETIC_ACTIVE_WEIGHT = 1.92f
const val MAGNETIC_INACTIVE_WEIGHT = 1.0f
const val MAGNETIC_WEIGHT_SUM = MAGNETIC_ACTIVE_WEIGHT + MAGNETIC_INACTIVE_WEIGHT * 2f

fun targetPillLeftRatio(selectedIndex: Int): Float {
    val clamped = selectedIndex.coerceIn(0, 2)
    return (clamped * MAGNETIC_INACTIVE_WEIGHT) / MAGNETIC_WEIGHT_SUM
}

const val TARGET_PILL_WIDTH_RATIO = MAGNETIC_ACTIVE_WEIGHT / MAGNETIC_WEIGHT_SUM

/**
 * 同步计算单帧底栏全部几何量：
 * - `fabProgress = 1f`（日程/月历）显示独立 `+` 按钮；
 * - `fabProgress = 0f`（我的）`+` 提前在 `fabProgress <= 0.18f` 淡出并彻底让出宽度，滑轨无缝右展至通栏；
 * - 磁吸滑块按归一化比例乘以实时 `innerTrackWidthPx`，保证与滑轨右展、三格收缩永远 0px 错位。
 */
fun computeMagneticDockGeometry(
    rowWidthPx: Int,
    fabProgress: Float,
    tabWeights: List<Float>,
    pillLeftRatio: Float,
    addButtonSizePx: Int,
    addGapPx: Int,
    trackHorizontalPaddingPx: Int,
): MagneticDockGeometry {
    require(tabWeights.size == 3) { "MagneticDock requires exactly 3 tab weights" }
    val safeRowWidth = rowWidthPx.coerceAtLeast(1)
    val p = fabProgress.coerceIn(0f, 1.08f)
    val clampedP = fabProgress.coerceIn(0f, 1f)

    val addSlotW = (addButtonSizePx * p).roundToInt().coerceIn(0, safeRowWidth)
    val addGapW = (addGapPx * p).roundToInt().coerceIn(0, (safeRowWidth - addSlotW).coerceAtLeast(0))
    val dockW = (safeRowWidth - addSlotW - addGapW).coerceAtLeast(1)
    val innerW = (dockW - trackHorizontalPaddingPx * 2).coerceAtLeast(1)

    val safeWeights = tabWeights.map { it.coerceAtLeast(0.5f) }
    val sumW = safeWeights.sum()
    val e1Max = (innerW - 2).coerceAtLeast(0)
    val edge1 = (innerW * (safeWeights[0] / sumW)).roundToInt().coerceIn(0, e1Max)
    val e2Max = (innerW - 1).coerceAtLeast(edge1)
    val edge2 = (innerW * ((safeWeights[0] + safeWeights[1]) / sumW)).roundToInt().coerceIn(edge1, e2Max)
    val xs = intArrayOf(0, edge1, edge2)
    val widths = intArrayOf(edge1, edge2 - edge1, innerW - edge2)

    val reveals = safeWeights.map { w ->
        ((w - 1.22f) / (MAGNETIC_ACTIVE_WEIGHT - 1.22f)).coerceIn(0f, 1f)
    }

    val clampedLeftRatio = pillLeftRatio.coerceIn(0f, (1f - TARGET_PILL_WIDTH_RATIO).coerceAtLeast(0f))
    val pillX = (innerW * clampedLeftRatio).roundToInt().coerceIn(0, (innerW - 1).coerceAtLeast(0))
    val pillEnd = (innerW * (clampedLeftRatio + TARGET_PILL_WIDTH_RATIO)).roundToInt().coerceIn(pillX, innerW)
    val pillW = (pillEnd - pillX).coerceIn(1, (innerW - pillX).coerceAtLeast(1))

    return MagneticDockGeometry(
        dockWidthPx = dockW,
        innerTrackWidthPx = innerW,
        addSlotWidthPx = addSlotW,
        addGapWidthPx = addGapW,
        addScale = 0.45f + 0.55f * p,
        addRotationDeg = (1f - clampedP) * -45f,
        addAlpha = ((clampedP - 0.18f) / 0.82f).coerceIn(0f, 1f),
        tabXsPx = xs.toList(),
        tabWidthsPx = widths.toList(),
        labelReveals = reveals,
        pillXPx = pillX,
        pillWidthPx = pillW,
    )
}

/** 根据目标页与起始页计算切页滑动方向：+1 从右进入，-1 从左进入。 */
fun clientPageSlideDirection(from: ClientPage, to: ClientPage): Int {
    fun rank(page: ClientPage): Int = when (page) {
        ClientPage.TASKS -> 0
        ClientPage.CALENDAR -> 1
        ClientPage.ACCOUNT -> 2
        ClientPage.SYNC -> 3
        ClientPage.CONNECTION -> 4
    }
    return if (rank(to) >= rank(from)) 1 else -1
}

private data class DockTabSpec(
    val page: ClientPage,
    val label: String,
    val icon: ImageVector,
)

private val DOCK_TABS = listOf(
    DockTabSpec(ClientPage.TASKS, "日程", AgendumIcons.Tasks),
    DockTabSpec(ClientPage.CALENDAR, "月历", AgendumIcons.Calendar),
    DockTabSpec(ClientPage.ACCOUNT, "我的", AgendumIcons.Account),
)

/**
 * 青岚磁吸底栏：
 * - 单帧 `updateTransition` + 自定义 `Layout` 同步驱动 `+` 显隐、滑轨右展、三格弹性收缩与翡翠磁吸滑块；
 * - 切到「我的」时 `+` 号缩旋淡出并释放右侧空间，切回「日程/月历」时丝滑恢复。
 */
@Composable
fun MagneticFusionDock(
    selectedDestination: ClientPage,
    showAddButton: Boolean,
    conflictCount: Int,
    onSelectTab: (ClientPage) -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extColors = LocalAgendumColors.current
    val reducedMotion = LocalReducedMotion.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val selectedIndex = when (selectedDestination) {
        ClientPage.TASKS -> 0
        ClientPage.CALENDAR -> 1
        else -> 2
    }

    val transition = updateTransition(
        targetState = selectedIndex to showAddButton,
        label = "MagneticFusionDock",
    )

    val fabProgress by transition.animateFloat(
        transitionSpec = {
            if (reducedMotion) snap() else spring(dampingRatio = 0.81f, stiffness = 340f)
        },
        label = "fabProgress",
    ) { (_, showAdd) -> if (showAdd) 1f else 0f }

    val w0 by transition.animateFloat(
        transitionSpec = {
            if (reducedMotion) snap() else spring(dampingRatio = 0.74f, stiffness = 380f)
        },
        label = "tabWeight0",
    ) { (idx, _) -> if (idx == 0) MAGNETIC_ACTIVE_WEIGHT else MAGNETIC_INACTIVE_WEIGHT }

    val w1 by transition.animateFloat(
        transitionSpec = {
            if (reducedMotion) snap() else spring(dampingRatio = 0.74f, stiffness = 380f)
        },
        label = "tabWeight1",
    ) { (idx, _) -> if (idx == 1) MAGNETIC_ACTIVE_WEIGHT else MAGNETIC_INACTIVE_WEIGHT }

    val w2 by transition.animateFloat(
        transitionSpec = {
            if (reducedMotion) snap() else spring(dampingRatio = 0.74f, stiffness = 380f)
        },
        label = "tabWeight2",
    ) { (idx, _) -> if (idx == 2) MAGNETIC_ACTIVE_WEIGHT else MAGNETIC_INACTIVE_WEIGHT }

    val pillLeftRatio by transition.animateFloat(
        transitionSpec = {
            if (reducedMotion) snap() else spring(dampingRatio = 0.74f, stiffness = 380f)
        },
        label = "pillLeftRatio",
    ) { (idx, _) -> targetPillLeftRatio(idx) }

    val dockHeightPx = with(density) { 60.dp.roundToPx() }
    val addSizePx = with(density) { 52.dp.roundToPx() }
    val addGapPx = with(density) { 10.dp.roundToPx() }
    val trackPadPx = with(density) { 4.dp.roundToPx() }
    val trackShape = RoundedCornerShape(20.dp)
    val pillShape = RoundedCornerShape(16.dp)

    val addInteraction = remember { MutableInteractionSource() }
    val addPressed by addInteraction.collectIsPressedAsState()
    val addPressScale by animateFloatAsState(
        targetValue = if (addPressed && !reducedMotion) 0.93f else 1f,
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.65f, stiffness = 680f),
        label = "addPressScale",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Layout(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            content = {
                // Child 0: 磁吸滑轨容器（内含：背景槽 + 实心翡翠滑块 + 3 个可收缩 Tab）
                Box(
                    modifier = Modifier
                        .shadow(
                            elevation = if (extColors.isDark) 10.dp else 8.dp,
                            shape = trackShape,
                            ambientColor = extColors.cardShadowAmbient,
                            spotColor = extColors.cardShadowAmbient,
                        )
                        .clip(trackShape)
                        .background(extColors.traySurface)
                        .border(1.dp, extColors.trayBorder, trackShape),
                ) {
                    Layout(
                        modifier = Modifier.fillMaxSize(),
                        content = {
                            // Inner 0: 实心翡翠磁吸滑块
                            Box(
                                modifier = Modifier
                                    .shadow(
                                        elevation = if (extColors.isDark) 4.dp else 5.dp,
                                        shape = pillShape,
                                        ambientColor = colorScheme.primary.copy(alpha = 0.28f),
                                        spotColor = colorScheme.primary.copy(alpha = 0.28f),
                                    )
                                    .clip(pillShape)
                                    .background(colorScheme.primary),
                            )

                            // Inner 1..3: 三个 Tab
                            val weights = listOf(w0, w1, w2)
                            DOCK_TABS.forEachIndexed { index, spec ->
                                val isSelected = index == selectedIndex
                                val labelP = ((weights[index] - 1.22f) / (MAGNETIC_ACTIVE_WEIGHT - 1.22f))
                                    .coerceIn(0f, 1f)
                                val contentColor by animateColorAsState(
                                    targetValue = if (isSelected) colorScheme.onPrimary else colorScheme.onSurfaceVariant,
                                    animationSpec = if (reducedMotion) snap() else tween(180),
                                    label = "dockTabColor-$index",
                                )
                                val tabInteraction = remember { MutableInteractionSource() }
                                val tabPressed by tabInteraction.collectIsPressedAsState()
                                val tabScale by animateFloatAsState(
                                    targetValue = if (tabPressed && !reducedMotion) 0.94f else 1f,
                                    animationSpec = if (reducedMotion) snap() else spring(0.68f, 650f),
                                    label = "dockTabScale-$index",
                                )
                                val showConflictDot = spec.page == ClientPage.ACCOUNT && conflictCount > 0

                                Row(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .graphicsLayer {
                                            scaleX = tabScale
                                            scaleY = tabScale
                                        }
                                        .clip(pillShape)
                                        .clickable(
                                            interactionSource = tabInteraction,
                                            indication = null,
                                            role = Role.Tab,
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onSelectTab(spec.page)
                                            },
                                        )
                                        .semantics(mergeDescendants = true) {
                                            selected = isSelected
                                            contentDescription = if (showConflictDot) {
                                                "${spec.label}，${conflictCount}项冲突"
                                            } else {
                                                spec.label
                                            }
                                        },
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = spec.icon,
                                            contentDescription = null,
                                            tint = contentColor,
                                            modifier = Modifier.size(21.dp),
                                        )
                                        if (showConflictDot) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .offset(x = 5.dp, y = (-3).dp)
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(colorScheme.error),
                                            )
                                        }
                                    }

                                    // 始终保留在组合与语义树中以兼容 onNodeWithText("我的")，收缩态按 0 宽度/0 透明度平滑隐藏。
                                    Text(
                                        text = spec.label,
                                        color = contentColor,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Clip,
                                        modifier = Modifier
                                            .alpha(labelP)
                                            .layout { measurable, constraints ->
                                                val placeable = measurable.measure(
                                                    constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity),
                                                )
                                                val gapPx = (6.dp.toPx() * labelP).roundToInt()
                                                val visibleW = (placeable.width * labelP).roundToInt()
                                                val totalW = if (visibleW > 0) visibleW + gapPx else 0
                                                layout(totalW, placeable.height) {
                                                    if (visibleW > 0) {
                                                        placeable.placeRelative(gapPx, 0)
                                                    }
                                                }
                                            },
                                    )
                                }
                            }
                        },
                    ) { measurables, constraints ->
                        val innerW = (constraints.maxWidth - trackPadPx * 2).coerceAtLeast(1)
                        val innerH = (constraints.maxHeight - trackPadPx * 2).coerceAtLeast(1)
                        val geom = computeMagneticDockGeometry(
                            rowWidthPx = constraints.maxWidth,
                            fabProgress = 0f,
                            tabWeights = listOf(w0, w1, w2),
                            pillLeftRatio = pillLeftRatio,
                            addButtonSizePx = 0,
                            addGapPx = 0,
                            trackHorizontalPaddingPx = trackPadPx,
                        )
                        val pillPlaceable = measurables[0].measure(
                            Constraints.fixed(geom.pillWidthPx, innerH),
                        )
                        val tabPlaceables = (0 until 3).map { i ->
                            measurables[i + 1].measure(
                                Constraints.fixed(geom.tabWidthsPx[i], innerH),
                            )
                        }
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            pillPlaceable.placeRelative(trackPadPx + geom.pillXPx, trackPadPx)
                            for (i in 0 until 3) {
                                tabPlaceables[i].placeRelative(trackPadPx + geom.tabXsPx[i], trackPadPx)
                            }
                        }
                    }
                }

                // Child 1: 独立方圆角新建按钮（切到「我的」时缩旋淡出并收起占位）
                val geomForAdd = computeMagneticDockGeometry(
                    rowWidthPx = 1000,
                    fabProgress = fabProgress,
                    tabWeights = listOf(w0, w1, w2),
                    pillLeftRatio = pillLeftRatio,
                    addButtonSizePx = addSizePx,
                    addGapPx = addGapPx,
                    trackHorizontalPaddingPx = trackPadPx,
                )
                val addInteractive = fabProgress >= 0.18f && showAddButton
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .graphicsLayer {
                            scaleX = geomForAdd.addScale * addPressScale
                            scaleY = geomForAdd.addScale * addPressScale
                            rotationZ = geomForAdd.addRotationDeg
                            alpha = geomForAdd.addAlpha
                        }
                        .shadow(
                            elevation = if (geomForAdd.addAlpha > 0.2f) 8.dp else 0.dp,
                            shape = pillShape,
                            ambientColor = colorScheme.primary.copy(alpha = 0.3f),
                            spotColor = colorScheme.primary.copy(alpha = 0.3f),
                        )
                        .clip(pillShape)
                        .background(colorScheme.primary)
                        .clickable(
                            enabled = addInteractive,
                            interactionSource = addInteraction,
                            indication = null,
                            role = Role.Button,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onAddClick()
                            },
                        )
                        .semantics {
                            contentDescription = "新建日程"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = AgendumIcons.Add,
                        contentDescription = null,
                        tint = colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            },
        ) { measurables, constraints ->
            val rowW = constraints.maxWidth
            val geom = computeMagneticDockGeometry(
                rowWidthPx = rowW,
                fabProgress = fabProgress,
                tabWeights = listOf(w0, w1, w2),
                pillLeftRatio = pillLeftRatio,
                addButtonSizePx = addSizePx,
                addGapPx = addGapPx,
                trackHorizontalPaddingPx = trackPadPx,
            )
            val dockPlaceable = measurables[0].measure(
                Constraints.fixed(geom.dockWidthPx, dockHeightPx),
            )
            val addPlaceable = measurables[1].measure(
                Constraints.fixed(addSizePx, addSizePx),
            )
            layout(rowW, dockHeightPx) {
                dockPlaceable.placeRelative(0, 0)
                if (geom.addAlpha > 0.01f) {
                    val addCenterX = geom.dockWidthPx + geom.addGapWidthPx + (geom.addSlotWidthPx / 2)
                    val addLeft = addCenterX - (addSizePx / 2)
                    val addTop = (dockHeightPx - addSizePx) / 2
                    addPlaceable.placeRelative(addLeft, addTop)
                }
            }
        }
    }
}

/**
 * 磁吸分段选择器：用于编辑页「截止时间类型」（不设置 / 仅日期 / 日期和时刻），
 * 浅薄荷冰雾内凹底槽 + 弹簧滑移清露翡翠实心胶囊，零硬切。
 */
@Composable
fun MagneticSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extColors = LocalAgendumColors.current
    val reducedMotion = LocalReducedMotion.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val animatedIndex by animateFloatAsState(
        targetValue = selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0)).toFloat(),
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.72f, stiffness = 460f),
        label = "segmentedSlider",
    )

    val outerShape = RoundedCornerShape(14.dp)
    val thumbShape = RoundedCornerShape(11.dp)
    val padPx = with(density) { 4.dp.roundToPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(outerShape)
            .background(extColors.traySurface)
            .border(1.dp, extColors.trayBorder, outerShape),
    ) {
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                Box(
                    modifier = Modifier
                        .shadow(
                            elevation = 3.dp,
                            shape = thumbShape,
                            ambientColor = colorScheme.primary.copy(alpha = 0.2f),
                            spotColor = colorScheme.primary.copy(alpha = 0.2f),
                        )
                        .clip(thumbShape)
                        .background(colorScheme.primary),
                )
                options.forEachIndexed { index, label ->
                    val isSelected = index == selectedIndex
                    val textColor by animateColorAsState(
                        targetValue = if (isSelected) colorScheme.onPrimary else colorScheme.onSurfaceVariant,
                        animationSpec = if (reducedMotion) snap() else tween(160),
                        label = "segText-$index",
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(thumbShape)
                            .clickable(
                                enabled = enabled,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.RadioButton,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelect(index)
                                },
                            )
                            .semantics { selected = isSelected },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = if (enabled) textColor else textColor.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            val count = options.size.coerceAtLeast(1)
            val innerW = (constraints.maxWidth - padPx * 2).coerceAtLeast(1)
            val innerH = (constraints.maxHeight - padPx * 2).coerceAtLeast(1)
            val slotW = innerW / count
            val thumbW = if (count > 0) (innerW.toFloat() / count).roundToInt() else innerW
            val thumbX = ((innerW.toFloat() / count) * animatedIndex).roundToInt()
                .coerceIn(0, (innerW - thumbW).coerceAtLeast(0))

            val thumbPlaceable = measurables[0].measure(Constraints.fixed(thumbW, innerH))
            val optionPlaceables = (0 until count).map { i ->
                val w = if (i == count - 1) (innerW - slotW * (count - 1)).coerceAtLeast(1) else slotW
                measurables[i + 1].measure(Constraints.fixed(w, innerH))
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                thumbPlaceable.placeRelative(padPx + thumbX, padPx)
                for (i in 0 until count) {
                    optionPlaceables[i].placeRelative(padPx + slotW * i, padPx)
                }
            }
        }
    }
}

/**
 * 青岚琉璃无边框漫射卡片：浅色模式靠柔和青雾投影区分层级，深色模式附带 1px 顶部微高光，杜绝双层鬼影框。
 */
@Composable
fun MistCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    containerColor: Color = LocalAgendumColors.current.cardSurface,
    elevation: Dp = 6.dp,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val extColors = LocalAgendumColors.current
    val shape: Shape = RoundedCornerShape(cornerRadius)
    val topHighlight = extColors.cardTopHighlight

    Surface(
        modifier = modifier
            .shadow(
                elevation = if (extColors.isDark) 2.dp else elevation,
                shape = shape,
                ambientColor = extColors.cardShadowAmbient,
                spotColor = extColors.cardShadowAmbient,
            )
            .drawWithContent {
                drawContent()
                if (extColors.isDark && topHighlight != Color.Transparent) {
                    drawLine(
                        color = topHighlight,
                        start = Offset(cornerRadius.toPx() * 0.6f, 0.5f),
                        end = Offset(size.width - cornerRadius.toPx() * 0.6f, 0.5f),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            },
        shape = shape,
        color = containerColor,
        border = border,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(content = content)
    }
}
