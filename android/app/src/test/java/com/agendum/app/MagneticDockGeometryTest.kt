package com.agendum.app

import com.agendum.app.ui.ClientPage
import com.agendum.app.ui.components.MAGNETIC_ACTIVE_WEIGHT
import com.agendum.app.ui.components.MAGNETIC_INACTIVE_WEIGHT
import com.agendum.app.ui.components.clientPageSlideDirection
import com.agendum.app.ui.components.computeMagneticDockGeometry
import com.agendum.app.ui.components.targetPillLeftRatio
import com.agendum.app.ui.screens.calendarDensityDotCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁定青岚磁吸底栏、切页方向与月历密度点的几何与边界契约，防止遮挡与越界回归。 */
class MagneticDockGeometryTest {
    @Test
    fun hidingAddButtonExpandsDockToFullRowWithoutOverlap() {
        val rowWidth = 1080
        val addSize = 156
        val gap = 30
        val pad = 12

        val visible = computeMagneticDockGeometry(
            rowWidthPx = rowWidth,
            fabProgress = 1f,
            tabWeights = listOf(MAGNETIC_ACTIVE_WEIGHT, MAGNETIC_INACTIVE_WEIGHT, MAGNETIC_INACTIVE_WEIGHT),
            pillLeftRatio = targetPillLeftRatio(0),
            addButtonSizePx = addSize,
            addGapPx = gap,
            trackHorizontalPaddingPx = pad,
        )
        assertEquals(rowWidth - addSize - gap, visible.dockWidthPx)
        assertEquals(addSize, visible.addSlotWidthPx)
        assertEquals(gap, visible.addGapWidthPx)
        assertEquals(1f, visible.addAlpha, 0.001f)
        assertEquals(0f, visible.addRotationDeg, 0.001f)
        assertEquals(visible.innerTrackWidthPx, visible.tabWidthsPx.sum())
        assertTrue(visible.pillXPx >= 0)
        assertTrue(visible.pillXPx + visible.pillWidthPx <= visible.innerTrackWidthPx)
        assertEquals(1f, visible.labelReveals[0], 0.001f)
        assertEquals(0f, visible.labelReveals[1], 0.001f)
        assertEquals(0f, visible.labelReveals[2], 0.001f)

        val hidden = computeMagneticDockGeometry(
            rowWidthPx = rowWidth,
            fabProgress = 0f,
            tabWeights = listOf(MAGNETIC_INACTIVE_WEIGHT, MAGNETIC_INACTIVE_WEIGHT, MAGNETIC_ACTIVE_WEIGHT),
            pillLeftRatio = targetPillLeftRatio(2),
            addButtonSizePx = addSize,
            addGapPx = gap,
            trackHorizontalPaddingPx = pad,
        )
        assertEquals(rowWidth, hidden.dockWidthPx)
        assertEquals(0, hidden.addSlotWidthPx)
        assertEquals(0, hidden.addGapWidthPx)
        assertEquals(0f, hidden.addAlpha, 0.001f)
        assertEquals(-45f, hidden.addRotationDeg, 0.001f)
        assertEquals(hidden.innerTrackWidthPx, hidden.tabWidthsPx.sum())
        assertEquals(hidden.tabXsPx[2], hidden.pillXPx)
        assertTrue(hidden.pillXPx + hidden.pillWidthPx <= hidden.innerTrackWidthPx)
        assertEquals(0f, hidden.labelReveals[0], 0.001f)
        assertEquals(0f, hidden.labelReveals[1], 0.001f)
        assertEquals(1f, hidden.labelReveals[2], 0.001f)
    }

    @Test
    fun addButtonFadesOutBeforeSlotFullyCollapses() {
        val midLow = computeMagneticDockGeometry(
            rowWidthPx = 900,
            fabProgress = 0.15f,
            tabWeights = listOf(1.2f, 1.0f, 1.72f),
            pillLeftRatio = 0.42f,
            addButtonSizePx = 156,
            addGapPx = 30,
            trackHorizontalPaddingPx = 12,
        )
        assertEquals("低于 0.18 阈值时 + 按钮必须完全淡出，避免被压扁成细条", 0f, midLow.addAlpha, 0.001f)
        assertTrue(midLow.dockWidthPx + midLow.addSlotWidthPx + midLow.addGapWidthPx <= 900)
        assertTrue(midLow.pillXPx + midLow.pillWidthPx <= midLow.innerTrackWidthPx)
    }

    @Test
    fun overshootAndTinyWidthNeverOverflowTrackBounds() {
        val narrow = computeMagneticDockGeometry(
            rowWidthPx = 220,
            fabProgress = 1.08f,
            tabWeights = listOf(2.1f, 0.8f, 0.8f),
            pillLeftRatio = 1.15f,
            addButtonSizePx = 100,
            addGapPx = 20,
            trackHorizontalPaddingPx = 8,
        )
        assertTrue(narrow.dockWidthPx >= 1)
        assertTrue(narrow.pillXPx >= 0)
        assertTrue(narrow.pillXPx + narrow.pillWidthPx <= narrow.innerTrackWidthPx)
        assertEquals(narrow.innerTrackWidthPx, narrow.tabWidthsPx.sum())
    }

    @Test
    fun clientPageSlideDirectionMatchesNavigationOrder() {
        assertEquals(1, clientPageSlideDirection(ClientPage.TASKS, ClientPage.CALENDAR))
        assertEquals(1, clientPageSlideDirection(ClientPage.CALENDAR, ClientPage.ACCOUNT))
        assertEquals(-1, clientPageSlideDirection(ClientPage.ACCOUNT, ClientPage.CALENDAR))
        assertEquals(-1, clientPageSlideDirection(ClientPage.ACCOUNT, ClientPage.TASKS))
        assertEquals(1, clientPageSlideDirection(ClientPage.ACCOUNT, ClientPage.SYNC))
        assertEquals(-1, clientPageSlideDirection(ClientPage.SYNC, ClientPage.ACCOUNT))
        assertEquals(-1, clientPageSlideDirection(ClientPage.CONNECTION, ClientPage.ACCOUNT))
    }

    @Test
    fun calendarDensityDotsCapAtThreeToPreventOverflow() {
        assertEquals(0, calendarDensityDotCount(-1))
        assertEquals(0, calendarDensityDotCount(0))
        assertEquals(1, calendarDensityDotCount(1))
        assertEquals(2, calendarDensityDotCount(2))
        assertEquals(3, calendarDensityDotCount(3))
        assertEquals(3, calendarDensityDotCount(12))
    }
}
