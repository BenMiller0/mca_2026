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
     *   Sat: ≥ 0.55  (rules out pinks and dull reds)
     *   Val: ≥ 0.30  (rules out very dark / shadowed reds)
     */
    private val HUE_RED_HI  = 15f     // upper bound of the 0°-side lobe
    private val HUE_RED_LO2 = 345f    // start of the 360°-side lobe
    private val SAT_MIN      = 0.55f
    private val VAL_MIN      = 0.30f

    private val MIN_RED_PIXELS   = 8       // below this → noise, not a dot
    private val CONFIDENCE_PIXELS = 80f    // pixels for full confidence

    // ── Gesture state ─────────────────────────────────────────────────────────

    private val posHistory = ArrayDeque<FloatArray>()  // [normX, normY, timestampMs]
    private val HISTORY_MAX       = 45
    private val GESTURE_WINDOW_MS = 1200L
    private val MIN_GESTURE_DIST  = 0.10f
    private val SPELL_COOLDOWN_MS = 1500L
    private var lastSpellTime     = 0L

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
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val maxC  = maxOf(rf, gf, bf)
        val minC  = minOf(rf, gf, bf)
        val delta = maxC - minC

        if (delta < 0.04f) return false   // achromatic

        // Only qualify if red is the dominant channel
        if (maxC != rf) return false

        val sat = delta / maxC
        val v   = maxC
        if (sat < SAT_MIN || v < VAL_MIN) return false

        // Hue in [0, HUE_RED_HI] or [HUE_RED_LO2, 360)
        val hue = run {
            var h = 60f * ((gf - bf) / delta)
            if (h < 0f) h += 360f
            h
        }
        return hue <= HUE_RED_HI || hue >= HUE_RED_LO2
    }

    // ── Gesture / spell recognition ───────────────────────────────────────────

    /**
     * Spell mapping:
     *   LUMOS            – left → right swipe  (lights on)
     *   NOX              – right → left swipe  (lights off)
     *   EXPELLIARMUS     – upward swipe
     *   ACCIO            – downward swipe
     */
    private fun recogniseSpell(now: Long): String {
        val recent = posHistory.filter { now - it[2].toLong() < GESTURE_WINDOW_MS }
        if (recent.size < 8) return ""

        val dX   = recent.last()[0] - recent.first()[0]
        val dY   = recent.last()[1] - recent.first()[1]
        val dist = sqrt(dX * dX + dY * dY)
        if (dist < MIN_GESTURE_DIST) return ""

        val adX = abs(dX); val adY = abs(dY)
        return when {
            adX > adY * 1.5f && dX > 0  -> "LUMOS"
            adX > adY * 1.5f && dX < 0  -> "NOX"
            adY > adX * 1.5f && dY < 0  -> "EXPELLIARMUS"
            adY > adX * 1.5f && dY > 0  -> "ACCIO"
            else                          -> ""
        }
    }

    companion object {
        @Suppress("unused")
        private const val TAG = "WandDetector"
    }
}
