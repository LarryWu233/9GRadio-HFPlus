package com.radiosport.ninegradio.scanner

import kotlin.math.*

/** Broadcast candidate detector, separate from general-purpose/data scanning. */
object EtmSignalDetector {
    data class Candidate(val hz: Long, val powerDb: Float, val floorDb: Float)
    fun detect(db: FloatArray, center: Long, rate: Int, fm: Boolean, margin: Float): List<Candidate> {
        if (db.size < 64 || rate <= 0) return emptyList()
        val binHz = rate.toDouble() / db.size
        val edge = max(8, (db.size * 0.04).toInt())
        val dc = db.size / 2
        fun usable(i: Int) = i in edge until db.size - edge && abs(i - dc) > 6 && db[i].isFinite()
        fun floorAt(index: Int, inner: Int, outer: Int): Float {
            val values = (max(edge, index - outer)..min(db.lastIndex - edge, index + outer))
                .filter { usable(it) && abs(it - index) > inner }.map { db[it] }.sorted()
            return if (values.isEmpty()) -200f else values[values.size / 2]
        }
        val results = ArrayList<Candidate>()
        if (!fm) {
            val neighborhood = max(3, (2500 / binHz).roundToInt())
            for (i in edge until db.size - edge) {
                if (!usable(i) || (i - neighborhood..i + neighborhood).any { it != i && it in db.indices && db[it] > db[i] }) continue
                val floor = floorAt(i, max(3, (2000 / binHz).toInt()), max(12, (20000 / binHz).toInt()))
                // Hann-windowed carrier must have neighboring support; a single
                // corrupted bin, DC spike or a noise peak does not suffice.
                if (db[i] - floor < margin || max(db[i-1], db[i+1]) - floor < margin - 6) continue
                val hz = center + ((i - dc) * binHz).roundToLong()
                results += Candidate((hz / 5000.0).roundToLong() * 5000, db[i], floor)
            }
        } else {
            val half = (60000 / binHz).roundToInt().coerceAtLeast(3)
            val lo = center - rate / 2 + (edge + half) * binHz
            val hi = center + rate / 2 - (edge + half) * binHz
            var hz = (ceil(lo / 100000).toLong() * 100000)
            while (hz <= hi) {
                val i = ((hz - center) / binHz + dc).roundToInt()
                val samples = (i-half..i+half).filter { usable(it) }
                val floor = floorAt(i, (140000 / binHz).toInt(), (300000 / binHz).toInt())
                if (samples.size > half) {
                    val mean = (10 * log10(samples.map { 10.0.pow(db[it] / 10.0) }.average().coerceAtLeast(1e-25))).toFloat()
                    val occupied = samples.count { db[it] > floor + 5 } / samples.size.toFloat()
                    // A broadcast FM signal occupies a channel. A thin carrier
                    // or a large isolated bin cannot pass this occupancy test.
                    if (occupied >= 0.45f && mean - floor >= max(8f, margin - 4)) results += Candidate(hz, mean, floor)
                }
                hz += 100000
            }
        }
        return merge(results, if (fm) 150000 else 5000)
    }
    fun merge(candidates: List<Candidate>, separation: Long): List<Candidate> {
        val selected = ArrayList<Candidate>()
        for (candidate in candidates.sortedByDescending { it.powerDb - it.floorDb }) {
            if (selected.none { abs(it.hz - candidate.hz) <= separation }) selected += candidate
        }
        return selected.sortedBy { it.hz }
    }
}
