package com.tom.animacolab.ui

/**
 * 生成页底部常驻进度条的可见性。
 *
 * 常驻条只在「进度卡完全移出视口」时出现：
 * - 卡片在视口下方（还没滚到）→ 显示常驻条；
 * - 卡片露头 / 完整可见 → 隐藏常驻条，避免同一进度显示两次（视觉 QA P1-1 / §6.4 的残余过渡态）；
 * - 卡片被滚到视口上方（只能发生在底部留白区）→ 再次显示。
 *
 * 纯几何判断，不依赖 Compose，便于 JVM 单测。
 *
 * @param active 是否有进行中的任务（没有任务时不显示）
 * @param cardMeasured 进度卡是否已经完成一次布局（首帧未测量时不显示，避免闪一下）
 * @param cardTopInViewportPx 进度卡上边缘相对视口顶部的距离（可为负）
 * @param cardHeightPx 进度卡高度
 * @param viewportHeightPx 滚动区域高度
 */
internal fun shouldShowStickyProgress(
    active: Boolean,
    cardMeasured: Boolean,
    cardTopInViewportPx: Long,
    cardHeightPx: Int,
    viewportHeightPx: Int,
): Boolean {
    if (!active || !cardMeasured || cardHeightPx <= 0 || viewportHeightPx <= 0) return false
    val fullyBelow = cardTopInViewportPx >= viewportHeightPx
    val fullyAbove = cardTopInViewportPx + cardHeightPx <= 0
    return fullyBelow || fullyAbove
}
