package com.example.wandapplication

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlin.math.*

/**
 * Detects a brown wooden wand in a camera frame using HSV colour segmentation.
 *
 * Pipeline:
 *  1. Scale the input frame down to a small analysis resolution for speed.
 *  2. Collect all pixels whose HSV value falls in the "brown" range.
 *  3. Require a minimum pixel count and a minimum aspect-ratio (elongated shape).
 *  4. Run a 2-D PCA on the brown pixel cloud to find the wand's principal axis.
 *  5. The tip is the endpoint with the smaller Y value (higher in the frame).
 *  6. Accumulate tip positions over time and classify motion as a spell gesture.
 */
class StickDetector(private val context: Context) {

    // ── Result type ──────────────────────────────────────────────────────────

    data class WandDetectionResult(
        val isDetected: Boolean,
        /** Normalised 0-1 coordinates of the wand tip (left/top = 0). */
        val tipX: Float = 0f,
        val tipY: Float = 0f,
        /** Normalised coordinates of the wand base (held end). */
        val baseX: Float = 0f,
        val baseY: Float = 0f,
        /** Angle of the wand in degrees (0 = horizontal right, +90 = pointing down). */
        val angle: Float = 0f,
        val confidence: Float = 0f,
        /** Non-empty string when a spell gesture was just recognised. */
        val spell: String = "",
        val message: String = ""
    )

    // Keep old name as a type alias so existing call-sites still compile.
    @Suppress("unused")
    val StickDetectionResult get() = WandDetectionResult::class

    // ── Config ────────────────────────────────────────────────────────────────

    /** Resolution to which the input frame is scaled before analysis. */
    private val ANALYSIS_W = 320
    private val ANALYSIS_H = 240

    /** HSV bounds for "brown wand" colour.
     *  Hue 8-38°  (orange-brown spectrum)
     *  Sat 25-90% (not a muddy grey, not neon)
     *  Val 12-78% (not too dark / washed-out)
     */
    private val HUE_LO = 8f;  private val HUE_HI = 38f
    private val SAT_LO = 0.25f; private val SAT_HI = 0.90f
    private val VAL_LO = 0.12f; private val VAL_HI = 0.78f

    private val MIN_BROWN_PIXELS  = 18      // fewer → noise, not a wand
    private val MIN_ASPECT_RATIO  = 2.8f    // width-to-height or height-to-width
    private val CONFIDENCE_PIXELS = 250f    // pixels needed for max confidence

    // ── Gesture state ─────────────────────────────────────────────────────────

    /** Each entry: [normTipX, normTipY, timestampMs]. */
    private val posHistory = ArrayDeque<FloatArray>()
    private val HISTORY_MAX   = 45
    private val GESTURE_WINDOW_MS = 1200L
    private val MIN_GESTURE_DIST  = 0.12f   // normalised distance across analysis frame
    private val SPELL_COOLDOWN_MS = 1500L
    private var lastSpellTime = 0L

    // ── Public API ────────────────────────────────────────────────────────────

    /** Entry point used by MainActivity. */
    fun detectStick(bitmap: Bitmap): WandDetectionResult = detectWand(bitmap)

    fun detectWand(bitmap: Bitmap): WandDetectionResult {
        // 1. Scale down for performance
        val scaled = Bitmap.createScaledBitmap(bitmap, ANALYSIS_W, ANALYSIS_H, false)
        val w = scaled.width
        val h = scaled.height

        // 2. Read all pixels at once (fast bulk read)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled != bitmap) scaled.recycle()

        // 3. Collect brown-pixel coordinates
        val bx = mutableListOf<Float>()
        val by = mutableListOf<Float>()

        for (idx in pixels.indices) {
            val p = pixels[idx]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8)  and 0xFF
            val b =  p         and 0xFF
            if (isBrown(r, g, b)) {
                bx.add((idx % w).toFloat())
                by.add((idx / w).toFloat())
            }
        }

        if (bx.size < MIN_BROWN_PIXELS) {
            return WandDetectionResult(false, message = "Scanning… (${bx.size} brown px)")
        }

        // 4. Bounding-box aspect-ratio gate
        val minX = bx.min(); val maxX = bx.max()
        val minY = by.min(); val maxY = by.max()
        val bbW  = (maxX - minX).coerceAtLeast(1f)
        val bbH  = (maxY - minY).coerceAtLeast(1f)
        val ar   = if (bbW >= bbH) bbW / bbH else bbH / bbW

        if (ar < MIN_ASPECT_RATIO) {
            return WandDetectionResult(
                false, confidence = 0.15f,
                message = "Region not wand-shaped (AR ${"%.1f".format(ar)})"
            )
        }

        // 5. Centroid
        val cx = bx.average().toFloat()
        val cy = by.average().toFloat()

        // 6. 2-D PCA: covariance matrix of the pixel cloud
        var covXX = 0f; var covYY = 0f; var covXY = 0f
        for (i in bx.indices) {
            val dx = bx[i] - cx; val dy = by[i] - cy
            covXX += dx * dx; covYY += dy * dy; covXY += dx * dy
        }
        val n = bx.size.toFloat()
        covXX /= n; covYY /= n; covXY /= n

        // Angle of principal axis (largest eigenvector)
        val axisAngle = 0.5f * atan2(2f * covXY, covXX - covYY)
        val axDx = cos(axisAngle)
        val axDy = sin(axisAngle)

        // 7. Project every point; keep the two extreme ends
        var minProj = Float.MAX_VALUE; var maxProj = -Float.MAX_VALUE
        var minPt = Pair(bx[0], by[0])
        var maxPt = Pair(bx[0], by[0])

        for (i in bx.indices) {
            val proj = (bx[i] - cx) * axDx + (by[i] - cy) * axDy
            if (proj < minProj) { minProj = proj; minPt = Pair(bx[i], by[i]) }
            if (proj > maxProj) { maxProj = proj; maxPt = Pair(bx[i], by[i]) }
        }

        // 8. Convention: tip = the end higher in the frame (smaller Y)
        val (tipPx, basePx) = if (minPt.second <= maxPt.second) Pair(minPt, maxPt)
                               else                              Pair(maxPt, minPt)

        val tipXN  = tipPx.first  / w
        val tipYN  = tipPx.second / h
        val baseXN = basePx.first  / w
        val baseYN = basePx.second / h
        val angleDeg = Math.toDegrees(axisAngle.toDouble()).toFloat()
        val conf = (bx.size / CONFIDENCE_PIXELS).coerceIn(0.3f, 1.0f)

        // 9. Push to history and recognise gesture
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
            tipX  = tipXN,  tipY  = tipYN,
            baseX = baseXN, baseY = baseYN,
            angle = angleDeg,
            confidence = conf,
            spell = spell,
            message = "Wand $coordStr$spellStr"
        )
    }

    fun clearHistory() = posHistory.clear()

    fun close() { /* nothing to release */ }

    // ── Colour check ──────────────────────────────────────────────────────────

    private fun isBrown(r: Int, g: Int, b: Int): Boolean {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val maxC = maxOf(rf, gf, bf)
        val minC = minOf(rf, gf, bf)
        val delta = maxC - minC

        if (delta < 0.04f) return false   // nearly achromatic

        val hue = when {
            maxC == rf -> { var h = 60f * ((gf - bf) / delta); if (h < 0f) h += 360f; h }
            maxC == gf ->   60f * ((bf - rf) / delta) + 120f
            else        ->   60f * ((rf - gf) / delta) + 240f
        }
        val sat = delta / maxC
        val `val` = maxC

        return hue in HUE_LO..HUE_HI && sat in SAT_LO..SAT_HI && `val` in VAL_LO..VAL_HI
    }

    // ── Gesture / spell recognition ───────────────────────────────────────────

    /**
     * Simple trajectory classifier operating on recent tip positions.
     *
     * Spell mapping (each maps to a distinct Arduino action):
     *   LUMOS            – left → right horizontal swipe  (lights on)
     *   NOX              – right → left horizontal swipe  (lights off)
     *   EXPELLIARMUS     – upward swipe (tip moves toward top of frame)
     *   ACCIO            – downward swipe
     */
    private fun recogniseSpell(now: Long): String {
        val recent = posHistory.filter { now - it[2].toLong() < GESTURE_WINDOW_MS }
        if (recent.size < 8) return ""

        val dX = recent.last()[0] - recent.first()[0]
        val dY = recent.last()[1] - recent.first()[1]
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
