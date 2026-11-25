package com.ctoc.sender

enum class StreamResolution(
    val shortLabel: String,
    val menuLabel: String,
    val shortSidePx: Int,
    val bitrateBps: Int
) {
    HD_720P("720p", "720p (Stability - 6 Mbps)", 720, 6_000_000),
    FHD_1080P("1080p", "1080p (Quality - 12 Mbps)", 1080, 12_000_000);

    fun computeAlignedSize(displayWidth: Int, displayHeight: Int): Pair<Int, Int> {
        val isLandscape = displayWidth > displayHeight
        val longRatio = maxOf(displayWidth, displayHeight).toFloat() /
            minOf(displayWidth, displayHeight).coerceAtLeast(1)

        val shortAligned = alignTo16(shortSidePx)
        val longAligned = alignTo16((shortSidePx * longRatio).toInt())

        return if (isLandscape) longAligned to shortAligned else shortAligned to longAligned
    }

    private fun alignTo16(value: Int): Int = ((value + 8) / 16) * 16
}
