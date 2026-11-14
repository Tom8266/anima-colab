package com.tom.animacolab

import com.tom.animacolab.ui.shouldShowStickyProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 生成页常驻进度条的几何判定（视觉 QA §6.4：卡片可见时不得再显示常驻条）。 */
class StickyProgressTest {

    private val viewport = 2000
    private val cardHeight = 300

    @Test
    fun inactiveNeverShows() {
        assertFalse(
            shouldShowStickyProgress(
                active = false,
                cardMeasured = true,
                cardTopInViewportPx = 5000,
                cardHeightPx = cardHeight,
                viewportHeightPx = viewport,
            ),
        )
    }

    @Test
    fun unmeasuredCardNeverShows() {
        // 首帧还没量到卡片：先不显示，避免卡片就在视口内时闪一下常驻条。
        assertFalse(
            shouldShowStickyProgress(
                active = true,
                cardMeasured = false,
                cardTopInViewportPx = Long.MAX_VALUE,
                cardHeightPx = 0,
                viewportHeightPx = viewport,
            ),
        )
    }

    @Test
    fun cardBelowViewportShows() {
        // 卡片整张在视口下方：用户还看不到进度，显示常驻条。
        assertTrue(
            shouldShowStickyProgress(
                active = true,
                cardMeasured = true,
                cardTopInViewportPx = viewport.toLong(),
                cardHeightPx = cardHeight,
                viewportHeightPx = viewport,
            ),
        )
    }

    @Test
    fun cardPeekingFromBottomHides() {
        // 卡片刚露头（哪怕只露 1px）：隐藏常驻条，避免两层进度同屏。
        assertFalse(
            shouldShowStickyProgress(
                active = true,
                cardMeasured = true,
                cardTopInViewportPx = (viewport - 1).toLong(),
                cardHeightPx = cardHeight,
                viewportHeightPx = viewport,
            ),
        )
    }

    @Test
    fun fullyVisibleCardHides() {
        assertFalse(
            shouldShowStickyProgress(
                active = true,
                cardMeasured = true,
                cardTopInViewportPx = 1500,
                cardHeightPx = cardHeight,
                viewportHeightPx = viewport,
            ),
        )
    }

    @Test
    fun cardScrolledAboveViewportShows() {
        // 滚动到底部留白、卡片整体移出视口上方：重新显示常驻条。
        assertTrue(
            shouldShowStickyProgress(
                active = true,
                cardMeasured = true,
                cardTopInViewportPx = (-cardHeight).toLong(),
                cardHeightPx = cardHeight,
                viewportHeightPx = viewport,
            ),
        )
    }

    @Test
    fun cardSliverAtTopHides() {
        assertFalse(
            shouldShowStickyProgress(
                active = true,
                cardMeasured = true,
                cardTopInViewportPx = (-cardHeight + 1).toLong(),
                cardHeightPx = cardHeight,
                viewportHeightPx = viewport,
            ),
        )
    }

    @Test
    fun zeroSizedInputsNeverShow() {
        assertFalse(
            shouldShowStickyProgress(
                active = true,
                cardMeasured = true,
                cardTopInViewportPx = 5000,
                cardHeightPx = 0,
                viewportHeightPx = 0,
            ),
        )
    }
}
