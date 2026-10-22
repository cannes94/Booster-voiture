package com.cannes94.boostervoiture

object BoostMath {
    const val MIN_TOTAL = 100
    const val MAX_TOTAL = 150
    const val MAX_GAIN_MB = 1000
    private const val MB_PER_PERCENT = 20

    fun gainMb(totalPercent: Int): Int =
        ((totalPercent.coerceIn(MIN_TOTAL, MAX_TOTAL) - MIN_TOTAL) * MB_PER_PERCENT)
            .coerceIn(0, MAX_GAIN_MB)

    fun voiceTargetMb(centerHz: Int): Int = if (centerHz in 1000..4000) 300 else 0

    fun globalActive(masterOn: Boolean, globalEnabled: Boolean): Boolean =
        masterOn && globalEnabled
}
