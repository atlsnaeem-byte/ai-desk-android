package pk.aidesk.app.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Chart-pattern engine: port of the laptop's patterns/engine2.py (scan + detect_at + best_line).
 * Works on any timeframe. Causal: a swing high/low counts only after k more candles; breakouts need a CLOSE beyond the line.
 * Lines go through the swing pair with the most touches where no candle closed through the line.
 */
object Patterns {
    val BIAS = mapOf(
        "rectangle" to 0, "asc_triangle" to 1, "desc_triangle" to -1, "sym_triangle" to 0, "rising_wedge" to -1,
        "falling_wedge" to 1, "channel_up" to 0, "channel_down" to 0, "double_top" to -1, "double_bottom" to 1,
        "head_shoulders" to -1, "inv_head_shoulders" to 1, "bull_flag" to 1, "bear_flag" to -1, "bull_pennant" to 1,
        "bear_pennant" to -1,
    )
    val NAMES = mapOf(
        "rectangle" to "Rectangle", "asc_triangle" to "Ascending triangle", "desc_triangle" to "Descending triangle",
        "sym_triangle" to "Symmetrical triangle", "rising_wedge" to "Rising wedge", "falling_wedge" to "Falling wedge",
        "channel_up" to "Rising channel", "channel_down" to "Falling channel", "double_top" to "Double top",
        "double_bottom" to "Double bottom", "head_shoulders" to "Head & shoulders", "inv_head_shoulders" to "Inverse H&S",
        "bull_flag" to "Bull flag", "bear_flag" to "Bear flag", "bull_pennant" to "Bull pennant", "bear_pennant" to "Bear pennant",
    )
    private val REV = setOf("double_top", "double_bottom", "head_shoulders", "inv_head_shoulders")
    fun isRev(kind: String) = kind in REV

    data class Cfg(
        val k: Int = 3, val window: Int = 100, val minSpan: Int = 10, val minHeightAtr: Double = 1.5,
        val maxHeightAtr: Double = 10.0, val flatFrac: Double = 0.25, val touchTolAtr: Double = 0.35,
        val dtTol: Double = 0.15, val hsTol: Double = 0.25, val expiryFrac: Double = 0.8, val poleBars: Int = 20,
        val poleAtr: Double = 4.0,
    )

    /** price = p0 + slope * (i - i0), i = bar index */
    class Line(val i0: Int, val p0: Double, val slope: Double) {
        fun at(i: Int): Double = p0 + slope * (i - i0)
        fun at(i: Double): Double = p0 + slope * (i - i0)
    }

    class Piv(val conf: Int, val idx: Int, val price: Double, val typ: Int)

    /** Stop for a breakout: the other line (value at the breakout bar) or a fixed level. */
    class Stop(val line: Line?, val level: Double?) {
        fun at(i: Int): Double = line?.at(i) ?: level!!
    }

    class Point(val i: Int, val price: Double, val label: String)

    class Pat(
        val kind: String, val start: Int, val formed: Int, val expires: Int, val up: Line?, val dn: Line?,
        val stopLong: Stop?, val stopShort: Stop?, val height: Double, val touchUp: Int, val touchDn: Int,
        val points: List<Point>, val pole: Double,
    ) {
        val name: String get() = NAMES.getValue(kind)
        val bias: Int get() = BIAS.getValue(kind)
        val touches: Int get() = touchUp + touchDn
        val neck: Boolean get() = isRev(kind)
    }

    class Breakout(
        val i: Int, val pat: Pat, val side: Int, val level: Double, val close: Double, val other: Double,
        val atr: Double, val volRatio: Double,
    ) {
        val kind: String get() = pat.kind
        val height: Double get() = pat.height
    }

    class Result(val active: List<Pat>, val breakouts: List<Breakout>, val atr: DoubleArray, val lastIndex: Int)

    // ------------------------------------------------------------------ indicators (pandas ewm, adjust=False)
    fun atr(h: DoubleArray, l: DoubleArray, c: DoubleArray, n: Int = 14): DoubleArray {
        val out = DoubleArray(c.size)
        val a = 1.0 / n
        for (i in c.indices) {
            val pc = if (i == 0) c[0] else c[i - 1]
            val tr = max(h[i] - l[i], max(abs(h[i] - pc), abs(l[i] - pc)))
            out[i] = if (i == 0) tr else (1 - a) * out[i - 1] + a * tr
        }
        return out
    }

    fun ema(x: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(x.size)
        val a = 2.0 / (n + 1)
        for (i in x.indices) out[i] = if (i == 0) x[0] else (1 - a) * out[i - 1] + a * x[i]
        return out
    }

    /** +1 if close > EMA200 and EMA50 > EMA200, -1 if both below, else 0 (needs ~200 bars). */
    fun regime(close: DoubleArray): Int {
        if (close.size < 205) return 0
        val e50 = ema(close, 50); val e200 = ema(close, 200)
        val i = close.size - 1
        return when {
            close[i] > e200[i] && e50[i] > e200[i] -> 1
            close[i] < e200[i] && e50[i] < e200[i] -> -1
            else -> 0
        }
    }

    // ------------------------------------------------------------------ pivots
    fun rawPivots(h: DoubleArray, l: DoubleArray, k: Int): List<Piv> {
        val n = h.size
        val out = ArrayList<Piv>()
        for (i in k until n - k) {
            var hw = Double.NEGATIVE_INFINITY; var lw = Double.POSITIVE_INFINITY
            for (j in i - k..i + k) { if (h[j] > hw) hw = h[j]; if (l[j] < lw) lw = l[j] }
            val isH = h[i] == hw; val isL = l[i] == lw
            if (isH && isL) continue
            if (isH) out += Piv(i + k, i, h[i], 1) else if (isL) out += Piv(i + k, i, l[i], -1)
        }
        return out
    }

    private fun push(P: MutableList<Piv>, p: Piv): Boolean {
        if (P.isNotEmpty() && P.last().typ == p.typ) {
            val last = P.last()
            if ((p.typ == 1 && p.price >= last.price) || (p.typ == -1 && p.price <= last.price)) { P[P.size - 1] = p; return true }
            return false
        }
        P += p
        return true
    }

    // ------------------------------------------------------------------ lines
    private class LineFit(val line: Line, val touches: Int, val pts: List<Pair<Int, Double>>)

    private fun bestLine(piv: List<Pair<Int, Double>>, c: DoubleArray, conf: Int, typ: Int, tol: Double): LineFit? {
        val cand = piv.takeLast(5)
        var best: LineFit? = null
        var bestKey: Triple<Int, Int, Int>? = null
        for (a in cand.indices) for (b in a + 1 until cand.size) {
            val (i0, p0) = cand[a]; val (i1, p1) = cand[b]
            if (i1 - i0 < 3) continue
            val ln = Line(i0, p0, (p1 - p0) / (i1 - i0))
            var through = false
            for (i in i0..conf) {
                val lv = ln.at(i)
                if ((typ == 1 && c[i] > lv + 0.15 * tol) || (typ == -1 && c[i] < lv - 0.15 * tol)) { through = true; break }
            }
            if (through) continue
            val pts = ArrayList<Pair<Int, Double>>()
            var bad = false
            for ((i, p) in piv) {
                if (i < i0) continue
                val d = (p - ln.at(i)) * typ
                if (d > tol) { bad = true; break }
                if (abs(d) <= tol) pts += i to p
            }
            if (bad) continue
            val key = Triple(pts.size, if (b == cand.size - 1) 1 else 0, i1 - i0)
            if (bestKey == null || compareValuesBy(key, bestKey, { it.first }, { it.second }, { it.third }) > 0) {
                bestKey = key; best = LineFit(ln, pts.size, pts)
            }
        }
        return best
    }

    private fun meanOf(x: DoubleArray, from: Int, toIncl: Int): Double {
        var s = 0.0; var n = 0
        for (i in max(0, from)..min(x.size - 1, toIncl)) { s += x[i]; n++ }
        return if (n == 0) 0.0 else s / n
    }

    private fun detectAt(P: List<Piv>, c: DoubleArray, h: DoubleArray, l: DoubleArray, atr: DoubleArray, cfg: Cfg): List<Pat> {
        val out = ArrayList<Pat>()
        val conf = P.last().conf
        val a = atr[conf]
        if (!(a > 0)) return out
        val W = cfg.window
        val rec = P.takeLast(12).filter { conf - it.idx <= W }
        val highs = rec.filter { it.typ == 1 }.map { it.idx to it.price }
        val lows = rec.filter { it.typ == -1 }.map { it.idx to it.price }
        val tol = cfg.touchTolAtr * a

        fun pole(start: Int): Double {
            val s0 = max(0, start - cfg.poleBars)
            var hiAfter = Double.NEGATIVE_INFINITY; var loAfter = Double.POSITIVE_INFINITY
            for (i in start until min(h.size, start + 3)) { hiAfter = max(hiAfter, h[i]); loAfter = min(loAfter, l[i]) }
            var loBefore = Double.POSITIVE_INFINITY; var hiBefore = Double.NEGATIVE_INFINITY
            for (i in s0..start) { loBefore = min(loBefore, l[i]); hiBefore = max(hiBefore, h[i]) }
            val upPole = (hiAfter - loBefore) / a
            val dnPole = (hiBefore - loAfter) / a
            return if (upPole >= dnPole) upPole else -dnPole
        }

        // ---------------- trendline family
        if (highs.size >= 2 && lows.size >= 2) {
            val uf = bestLine(highs, c, conf, 1, tol)
            val df = bestLine(lows, c, conf, -1, tol)
            if (uf != null && df != null) {
                val up = uf.line; val dn = df.line
                val start = min(up.i0, dn.i0)
                val span = conf - start
                val height = max(up.at(start) - dn.at(start), up.at(conf) - dn.at(conf))
                val widthNow = up.at(conf) - dn.at(conf)
                if (span >= cfg.minSpan && height >= cfg.minHeightAtr * a && height <= cfg.maxHeightAtr * a && widthNow > 0.2 * height) {
                    val fl = cfg.flatFrac * height
                    val du = up.slope * span; val dd = dn.slope * span
                    val fu = abs(du) < fl; val fd = abs(dd) < fl
                    val conv = (du - dd) < -fl
                    val par = abs(du - dd) <= fl
                    var kind: String? = when {
                        fu && fd -> "rectangle"
                        fu && dd > 0 && conv -> "asc_triangle"
                        fd && du < 0 && conv -> "desc_triangle"
                        du < 0 && 0 < dd && conv -> "sym_triangle"
                        du > 0 && dd > 0 && conv && !(fu || fd) -> "rising_wedge"
                        du < 0 && dd < 0 && conv && !(fu || fd) -> "falling_wedge"
                        par && du > 0 && dd > 0 -> "channel_up"
                        par && du < 0 && dd < 0 -> "channel_down"
                        else -> null
                    }
                    if (kind != null) {
                        val pl = pole(start)
                        if (abs(pl) >= cfg.poleAtr && height <= 0.6 * abs(pl) * a) {
                            kind = when {
                                pl > 0 && kind in setOf("channel_down", "rectangle", "falling_wedge", "desc_triangle") -> "bull_flag"
                                pl > 0 && kind == "sym_triangle" -> "bull_pennant"
                                pl < 0 && kind in setOf("channel_up", "rectangle", "rising_wedge", "asc_triangle") -> "bear_flag"
                                pl < 0 && kind == "sym_triangle" -> "bear_pennant"
                                else -> kind
                            }
                        }
                        var exp = conf + max(3, (cfg.expiryFrac * span).toInt())
                        if (up.slope - dn.slope < 0) {
                            val apex = up.i0 + (dn.at(up.i0) - up.p0) / (up.slope - dn.slope)
                            exp = min(exp, apex.toInt())
                        }
                        if (exp > conf) {
                            val pts = uf.pts.map { Point(it.first, it.second, "") } + df.pts.map { Point(it.first, it.second, "") }
                            out += Pat(kind!!, start, conf, exp, up, dn, Stop(dn, null), Stop(up, null), height, uf.touches, df.touches, pts, pl)
                        }
                    }
                }
            }
        }
        // ---------------- double top / bottom
        if (P.size >= 3) {
            val p1 = P[P.size - 3]; val mid = P[P.size - 2]; val p2 = P[P.size - 1]
            if (p1.typ == p2.typ && conf - p1.idx <= W && p2.idx - p1.idx >= cfg.minSpan / 2) {
                val s = p2.typ
                val height = if (s == 1) max(p1.price, p2.price) - mid.price else mid.price - min(p1.price, p2.price)
                if (height >= cfg.minHeightAtr * a && height <= cfg.maxHeightAtr * a && abs(p1.price - p2.price) <= cfg.dtTol * height) {
                    val neck = Line(mid.idx, mid.price, 0.0)
                    val ext = if (s == 1) max(p1.price, p2.price) else min(p1.price, p2.price)
                    val pl = pole(p1.idx)
                    val exp = conf + (p2.idx - p1.idx)
                    if (s == 1 && c[conf] > mid.price)
                        out += Pat("double_top", p1.idx, conf, exp, null, neck, null, Stop(null, ext), height, 2, 2,
                            listOf(Point(p1.idx, p1.price, "Top 1"), Point(mid.idx, mid.price, "Neck"), Point(p2.idx, p2.price, "Top 2")), pl)
                    else if (s == -1 && c[conf] < mid.price)
                        out += Pat("double_bottom", p1.idx, conf, exp, neck, null, Stop(null, ext), null, height, 2, 2,
                            listOf(Point(p1.idx, p1.price, "Bottom 1"), Point(mid.idx, mid.price, "Neck"), Point(p2.idx, p2.price, "Bottom 2")), pl)
                }
            }
        }
        // ---------------- head & shoulders
        if (P.size >= 5) {
            val s1 = P[P.size - 5]; val t1 = P[P.size - 4]; val hd = P[P.size - 3]; val t2 = P[P.size - 2]; val s2 = P[P.size - 1]
            if (s1.typ == hd.typ && hd.typ == s2.typ && conf - s1.idx <= W && s2.idx - s1.idx >= cfg.minSpan) {
                val s = s2.typ
                val neck = Line(t1.idx, t1.price, (t2.price - t1.price) / (t2.idx - t1.idx))
                val height = abs(hd.price - neck.at(hd.idx))
                val shOk = (s == 1 && hd.price > max(s1.price, s2.price)) || (s == -1 && hd.price < min(s1.price, s2.price))
                if (shOk && abs(s1.price - s2.price) <= cfg.hsTol * height && height >= cfg.minHeightAtr * a && height <= cfg.maxHeightAtr * a) {
                    val pl = pole(s1.idx)
                    val pts = listOf(Point(s1.idx, s1.price, "LS"), Point(t1.idx, t1.price, ""), Point(hd.idx, hd.price, "Head"),
                        Point(t2.idx, t2.price, ""), Point(s2.idx, s2.price, "RS"))
                    val exp = conf + (s2.idx - hd.idx)
                    if (s == 1 && c[conf] > neck.at(conf))
                        out += Pat("head_shoulders", s1.idx, conf, exp, null, neck, null, Stop(null, s2.price), height, 2, 2, pts, pl)
                    else if (s == -1 && c[conf] < neck.at(conf))
                        out += Pat("inv_head_shoulders", s1.idx, conf, exp, neck, null, Stop(null, s2.price), null, height, 2, 2, pts, pl)
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ scan
    /** Scan CLOSED candles. Active = patterns still waiting for a breakout at the last bar. */
    fun scan(cd: Candles, cfg: Cfg = Cfg()): Result {
        val h = cd.high; val l = cd.low; val c = cd.close; val v = cd.volume
        val n = c.size
        val at = atr(h, l, c, 14)
        if (n < 30) return Result(emptyList(), emptyList(), at, n - 1)
        val vavg = DoubleArray(n) { Double.NaN }
        var s = 0.0
        for (i in 0 until n) {                                   // rolling(20).mean().shift(1)
            if (i >= 1) { s += v[i - 1]; if (i - 1 >= 20) s -= v[i - 21] }
            if (i >= 20) vavg[i] = s / 20
        }
        val byConf = HashMap<Int, MutableList<Piv>>()
        for (p in rawPivots(h, l, cfg.k)) byConf.getOrPut(p.conf) { ArrayList() } += p
        val P = ArrayList<Piv>()
        var active = ArrayList<Pat>()
        val ev = ArrayList<Breakout>()
        var tline: Pat? = null
        for (i in 0 until n) {
            if (active.isNotEmpty()) {
                val keep = ArrayList<Pat>()
                for (p in active) {
                    if (i > p.expires) continue
                    val u = p.up?.at(i); val d = p.dn?.at(i)
                    val side = if (u != null && c[i] > u) 1 else if (d != null && c[i] < d) -1 else 0
                    if (side == 0) {
                        if ((p.kind == "double_top" || p.kind == "head_shoulders") && c[i] > p.stopShort!!.at(i)) continue
                        if ((p.kind == "double_bottom" || p.kind == "inv_head_shoulders") && c[i] < p.stopLong!!.at(i)) continue
                        keep += p; continue
                    }
                    val lvl = if (side == 1) u!! else d!!
                    val other = (if (side == 1) p.stopLong else p.stopShort)?.at(i) ?: (lvl - side * p.height)
                    ev += Breakout(i, p, side, lvl, c[i], other, at[i], if (vavg[i] > 0) v[i] / vavg[i] else 1.0)
                }
                active = keep
            }
            byConf[i]?.forEach { rp ->
                if (!push(P, rp)) return@forEach
                if (P.size > 300) repeat(150) { P.removeAt(0) }
                for (p in detectAt(P, c, h, l, at, cfg)) {
                    if (isRev(p.kind)) active.add(p)
                    else {
                        tline?.let { active.remove(it) }
                        tline = p
                        active.add(p)
                    }
                }
            }
        }
        return Result(active.filter { it.expires >= n - 1 }, ev, at, n - 1)
    }
}
