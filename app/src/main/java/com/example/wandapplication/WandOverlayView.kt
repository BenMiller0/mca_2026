package com.example.wandapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay drawn on top of the camera PreviewView.
 *
 * Renders:
 *  • A gold dot at the detected wand tip.
 *  • A fading trail of recent tip positions.
 *  • The spell name centred on the view (fades out over ~1 s).
 */
class WandOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Paints ────────────────────────────────────────────────────────────────

    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 215, 0)   // bright gold
        style = Paint.Style.FILL
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val spellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 72f
        isFakeBoldText = true
        setShadowLayer(8f, 0f, 0f, Color.argb(180, 255, 165, 0))
    }

    private val coordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 200, 255, 200)
        textSize = 32f
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    // ── State ─────────────────────────────────────────────────────────────────

    /** Normalised [0,1] tip position, or (-1, -1) when no wand detected. */
    private var tipXNorm = -1f
    private var tipYNorm = -1f

    /** Trail: each entry is (pixelX, pixelY). */
    private val trail = ArrayDeque<Pair<Float, Float>>()
    private val MAX_TRAIL = 25

    private var spellText = ""
    private var spellAlpha = 0

    private var coordText = ""

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Call this from the analysis thread result (post to UI thread first).
     *
     * @param tipXNorm  Normalised X of wand tip (0 = left, 1 = right).
     * @param tipYNorm  Normalised Y of wand tip (0 = top,  1 = bottom).
     * @param spell     Non-empty string when a spell was just cast.
     */
    fun updateWand(tipXNorm: Float, tipYNorm: Float, spell: String) {
        if (width == 0 || height == 0) return

        val px = tipXNorm * width
        val py = tipYNorm * height

        this.tipXNorm = tipXNorm
        this.tipYNorm = tipYNorm

        trail.addLast(Pair(px, py))
        if (trail.size > MAX_TRAIL) trail.removeFirst()

        if (spell.isNotEmpty()) {
            spellText = spell
            spellAlpha = 255
            spellPaint.color = when (spell) {
                "SUMMON" -> Color.argb(255, 180, 80, 255)   // dark purple
                "LUMOS"  -> Color.argb(255, 255, 230, 100)  // warm gold
                else     -> Color.WHITE                       // PUSH = white
            }
            spellPaint.setShadowLayer(10f, 0f, 0f, when (spell) {
                "SUMMON" -> Color.argb(200, 100, 0, 200)
                "LUMOS"  -> Color.argb(180, 255, 165, 0)
                else     -> Color.argb(180, 255, 165, 0)
            })
        } else {
            // Fade out existing spell label
            spellAlpha = (spellAlpha - 6).coerceAtLeast(0)
        }

        coordText = "tip (${(tipXNorm * 100).toInt()}%, ${(tipYNorm * 100).toInt()}%)"

        invalidate()
    }

    /** Call when the wand is no longer visible. */
    fun clearWand() {
        tipXNorm = -1f; tipYNorm = -1f
        trail.clear()
        spellAlpha = (spellAlpha - 6).coerceAtLeast(0)
        coordText = ""
        invalidate()
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw motion trail
        trail.forEachIndexed { idx, (px, py) ->
            val fraction = (idx + 1).toFloat() / trail.size
            val alpha    = (255 * fraction * 0.7f).toInt()
            val radius   = 4f + 8f * fraction
            trailPaint.color = Color.argb(alpha, 255, 165, 0)
            canvas.drawCircle(px, py, radius, trailPaint)
        }

        // Draw tip dot (only if wand detected)
        if (tipXNorm >= 0f) {
            val px = tipXNorm * width
            val py = tipYNorm * height
            canvas.drawCircle(px, py, 18f, tipPaint)

            // Coordinate text near the tip
            if (coordText.isNotEmpty()) {
                val tx = (px + 22f).coerceAtMost(width - coordPaint.measureText(coordText) - 4f)
                val ty = (py - 10f).coerceAtLeast(coordPaint.textSize)
                canvas.drawText(coordText, tx, ty, coordPaint)
            }
        }

        // Draw spell name (centred, fades out)
        if (spellText.isNotEmpty() && spellAlpha > 0) {
            spellPaint.alpha = spellAlpha
            val tw = spellPaint.measureText(spellText)
            canvas.drawText(spellText, (width - tw) / 2f, height * 0.55f, spellPaint)
        }
    }
}
