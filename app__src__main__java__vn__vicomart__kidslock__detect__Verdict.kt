package vn.vicomart.kidslock.detect

enum class Verdict { CHILD, ADULT, NONE }

object VerdictRules {
    /** Minimum number of frames with an age estimate before a decision is made. */
    const val MIN_FRAMES = 2

    fun decide(samples: List<FaceSample>, threshold: Int): Verdict {
        val ages = samples.mapNotNull { it.age }
        if (ages.size < MIN_FRAMES) return Verdict.NONE
        val med = median(ages)
        val childShare = ages.count { it < threshold }.toFloat() / ages.size
        return if (med < threshold && childShare >= 0.6f) Verdict.CHILD else Verdict.ADULT
    }

    fun median(v: List<Float>): Float {
        val s = v.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
    }

    /** Consecutive "child" checks needed before locking, by sensitivity 0/1/2. */
    fun requiredStreak(sensitivity: Int): Int = when (sensitivity) {
        0 -> 3
        2 -> 1
        else -> 2
    }
}
