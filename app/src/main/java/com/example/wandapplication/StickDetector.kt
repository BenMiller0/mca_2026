package com.example.wandapplication

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlin.math.*

/**
 * Detects a wand tip by looking for a small **red dot** affixed to its end.
 *
 * Pipeline:
 *  1. Scale the frame to 320×240 for speed.
 *  2. Collect every pixel whose HSV falls in the vivid-red range.
 *     Red wraps around 0° in HSV, so we check H ∈ [0°,15°] ∪ [345°,360°].
 *  3. Require at least MIN_RED_PIXELS hits (filters out single stray pixels).
 *  4. Compute the centroid of the red blob → that is the wand-tip position.
 *  5. Optionally estimate angle from the bounding box of the blob.
 *  6. Accumulate tip positions over time and classify motion as a spell gesture.
 *
 * Why a red dot?
 *  Colour segmentation of a brown wand fails when a hand is holding it because
 *  the combined region is no longer elongated.  A vivid red marker is highly
 *  distinct from skin, wood, and typical backgrounds, so a simple blob-centroid
 *  gives a rock-solid tip coordinate with no shape assumptions needed.
 */
class StickDetector(private val context: Context) {

    // ── Result type ──────────────────────────────────────────────────────────

    data class WandDetectionResult(
        val isDetected: Boolean,
        /** Normalised 0-1 tip coordinates (left/top = 0). */
        val tipX: Float = 0f,
        val tipY: Float = 0f,
        /** Angle in degrees estimated from the red blob's bounding box. */
        val angle: Float = 0f,
        val confidence: Float = 0f,
        /** Non-empty when a spell gesture was just recognised. */
        val spell: String = "",
        val message: String = ""
    )

    // ── Config ────────────────────────────────────────────────────────────────

    private val ANALYSIS_W = 320
    private val ANALYSIS_H = 240

    /**
     * Vivid-red HSV bounds.
     *   Hue: wraps around 0° → check [0, HUE_RED_HI] and [HUE_RED_LO2, 360)
     *   Sat: ≥ 0.65  (rules out pinks and dull reds)
     *   Val: ≥ 0.35  (rules out very dark / shadowed reds)
     *
     * RGB ratio guards (the main skin-rejection layer):
     *   A vivid red sticker has R ≈ 220, G ≈ 30, B ≈ 30  → R/G ≈ 7
     *   Skin tone has            R ≈ 220, G ≈ 170, B ≈ 140 → R/G ≈ 1.3
     *   Requiring R > G * R_OVER_G and R > B * R_OVER_B eliminates skin reliably.
     */
    private val HUE_RED_HI  = 12f     // tight upper bound on the 0°-side lobe
    private val HUE_RED_LO2 = 348f    // tight start of the 360°-side lobe
    private val SAT_MIN      = 0.65f
    private val VAL_MIN      = 0.35f
    private val R_OVER_G     = 1.8f   // red channel must be 1.8× the green channel
    private val R_OVER_B     = 1.6f   // red channel must be 1.6× the blue channel

    private val MIN_RED_PIXELS   = 8       // below this → noise, not a dot
    private val CONFIDENCE_PIXELS = 80f    // pixels for full confidence

    // ── Gesture state ─────────────────────────────────────────────────────────

    private val posHistory = ArrayDeque<FloatArray>()  // [normX, normY, timestampMs]
    private val HISTORY_MAX       = 60

    // Gesture tuning
    // A flick moves ~0.2-0.4 normalised units in ~200-400 ms.
    // At ~20 analysis fps that is ~0.3 norm-units/second minimum.
    private val GESTURE_WINDOW_MS  = 700L   // only look at the last 700 ms
    private val MIN_FLICK_SPEED    = 0.30f  // normalised units / second — filters idle drift
    private val ACTIVE_SPEED_RATIO = 0.35f  // frames above (peak * ratio) count as "active"
    private val MIN_ACTIVE_FRAMES  = 3      // need at least this many fast frames
    private val DIR_DOMINANCE      = 1.8f   // |dominant axis| must be this × |other axis|
    private val DIR_CONSISTENCY    = 0.60f  // fraction of active frames that agree with net dir
    private val SPELL_COOLDOWN_MS  = 1800L
    private var lastSpellTime      = 0L

    // ── Public API ────────────────────────────────────────────────────────────

    fun detectStick(bitmap: Bitmap): WandDetectionResult = detectWand(bitmap)

    fun detectWand(bitmap: Bitmap): WandDetectionResult {
        val scaled = Bitmap.createScaledBitmap(bitmap, ANALYSIS_W, ANALYSIS_H, false)
        val w = scaled.width
        val h = scaled.height

        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled != bitmap) scaled.recycle()

        // ── 1. Collect red pixels ──────────────────────────────────────────
        var sumX = 0L; var sumY = 0L; var count = 0
        var minX = w;  var maxX = 0
        var minY = h;  var maxY = 0

        for (idx in pixels.indices) {
            val p = pixels[idx]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8)  and 0xFF
            val b =  p         and 0xFF
            if (isRed(r, g, b)) {
                val px = idx % w
                val py = idx / w
                sumX += px; sumY += py; count++
                if (px < minX) minX = px; if (px > maxX) maxX = px
                if (py < minY) minY = py; if (py > maxY) maxY = py
            }
        }

        if (count < MIN_RED_PIXELS) {
            return WandDetectionResult(false, message = "Scanning… no red tip detected ($count px)")
        }

        // ── 2. Centroid → tip coordinates ─────────────────────────────────
        val tipXN = (sumX.toFloat() / count) / w
        val tipYN = (sumY.toFloat() / count) / h

        // ── 3. Rough angle from bounding box ──────────────────────────────
        val bbW   = (maxX - minX).coerceAtLeast(1)
        val bbH   = (maxY - minY).coerceAtLeast(1)
        val angle = if (bbW >= bbH) 0f else 90f   // horizontal vs vertical blob

        val conf  = (count / CONFIDENCE_PIXELS).coerceIn(0.3f, 1.0f)

        // ── 4. History + gesture recognition ──────────────────────────────
        val now = System.currentTimeMillis()
        posHistory.addLast(floatArrayOf(tipXN, tipYN, now.toFloat()))
        while (posHistory.size > HISTORY_MAX) posHistory.removeFirst()

        val spell = if (now - lastSpellTime > SPELL_COOLDOWN_MS) {
            recogniseSpell(now).also { if (it.isNotEmpty()) lastSpellTime = now }
        } else ""

        val coordStr = "(${(tipXN * 100).toInt()}%, ${(tipYN * 100).toInt()}%)"
        val spellStr = if (spell.isNotEmpty()) " → $spell!" else ""

        return WandDetectionResult(
            isDetected = true,
            tipX       = tipXN,
            tipY       = tipYN,
            angle      = angle,
            confidence = conf,
            spell      = spell,
            message    = "Tip $coordStr  [${count}px]$spellStr"
        )
    }

    fun clearHistory() = posHistory.clear()
    fun close() { /* nothing to release */ }

    // ── Colour check ──────────────────────────────────────────────────────────

    private fun isRed(r: Int, g: Int, b: Int): Boolean {
        // Fast RGB ratio pre-check — eliminates skin before any float math
        if (g == 0 || b == 0) return false
        if (r.toFloat() / g < R_OVER_G) return false
        if (r.toFloat() / b < R_OVER_B) return false

        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val maxC  = maxOf(rf, gf, bf)
        val minC  = minOf(rf, gf, bf)
        val delta = maxC - minC

        if (delta < 0.04f) return false   // achromatic
        if (maxC != rf)    return false   // red must be the dominant channel

        val sat = delta / maxC
        val v   = maxC
        if (sat < SAT_MIN || v < VAL_MIN) return false

        // Hue in [0, HUE_RED_HI] or [HUE_RED_LO2, 360)
        var hue = 60f * ((gf - bf) / delta)
        if (hue < 0f) hue += 360f
        return hue <= HUE_RED_HI || hue >= HUE_RED_LO2
    }

    // ── Gesture / spell recognition ───────────────────────────────────────────

    /**
     * Two-spell classifier based on velocity analysis of recent tip positions.
     *
     *   LUMOS       – left → right flick
     *   WINGARDIUM  – top  → bottom flick
     *
     * Algorithm
     * ---------
     * 1. Compute instantaneous velocity between every consecutive stored position.
     * 2. Identify the peak speed in the window; gate out sessions that never moved fast.
     * 3. Collect "active" frames: those whose speed ≥ peak × ACTIVE_SPEED_RATIO.
     *    This isolates the actual flick and ignores slow drift before/after it.
     * 4. Compute the average velocity vector over active frames (net direction).
     * 5. Check directional consistency: ≥ DIR_CONSISTENCY fraction of active frames
     *    must have a velocity vector that points within 60° of the net direction
     *    (dot product > 0.5 with the unit net direction).
     * 6. If consistent, classify by which axis dominates the net direction.
     */
    private fun recogniseSpell(now: Long): String {
        val window = posHistory.filter { now - it[2].toLong() < GESTURE_WINDOW_MS }
        if (window.size < 4) return ""

        // ── 1. Instantaneous velocities (normalised units / second) ────────
        val n = window.size - 1
        val vx    = FloatArray(n)
        val vy    = FloatArray(n)
        val speed = FloatArray(n)

        for (i in 0 until n) {
            val dtMs = (window[i + 1][2] - window[i][2]).coerceAtLeast(1f)
            vx[i]    = (window[i + 1][0] - window[i][0]) / dtMs * 1000f
            vy[i]    = (window[i + 1][1] - window[i][1]) / dtMs * 1000f
            speed[i] = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
        }

        // ── 2. Peak speed gate ─────────────────────────────────────────────
        val peakSpeed = speed.max()
        if (peakSpeed < MIN_FLICK_SPEED) return ""

        // ── 3. Active frames (the burst of the flick) ──────────────────────
        val threshold  = peakSpeed * ACTIVE_SPEED_RATIO
        val activeIdx  = speed.indices.filter { speed[it] >= threshold }
        if (activeIdx.size < MIN_ACTIVE_FRAMES) return ""

        // ── 4. Net direction over active frames ────────────────────────────
        var netVx = 0f; var netVy = 0f
        for (i in activeIdx) { netVx += vx[i]; netVy += vy[i] }
        netVx /= activeIdx.size; netVy /= activeIdx.size

        val netSpeed = sqrt(netVx * netVx + netVy * netVy)
        if (netSpeed < 1e-6f) return ""

        // ── 5. Directional consistency check ──────────────────────────────
        var consistent = 0
        for (i in activeIdx) {
            val dot = (vx[i] * netVx + vy[i] * netVy) / (speed[i] * netSpeed)
            if (dot > 0.5f) consistent++   // within ~60° of the net direction
        }
        if (consistent.toFloat() / activeIdx.size < DIR_CONSISTENCY) return ""

        // ── 6. Classify ────────────────────────────────────────────────────
        val absX = abs(netVx); val absY = abs(netVy)
        return when {
            absX > absY * DIR_DOMINANCE && netVx > 0 -> "LUMOS"       // left → right
            absY > absX * DIR_DOMINANCE && netVy > 0 -> "WINGARDIUM"  // top  → bottom
            else                                      -> ""
        }
    }

    companion object {
        @Suppress("unused")
        private const val TAG = "WandDetector"
    }
}
