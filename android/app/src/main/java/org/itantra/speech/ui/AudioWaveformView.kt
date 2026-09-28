package org.itantra.speech.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * Lightweight, hardware-accelerated audio waveform visualizer.
 *
 * Uses zero heavy bitmap allocations or third-party libraries (< 500 KB RAM footprint).
 * Supports three visual modes:
 * - SPEAKING_LOCAL: Energetic emerald green / teal bars responding to microphone RMS.
 * - SPEAKING_REMOTE: Smooth cyan / cobalt blue wave representing incoming speech.
 * - BARGE_IN: Crimson / amber dynamic alert pulse.
 * - IDLE: Gentle breathing baseline wave.
 */
class AudioWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode {
        IDLE,
        SPEAKING_LOCAL,
        SPEAKING_REMOTE,
        BARGE_IN
    }

    private var currentMode: Mode = Mode.IDLE
    private var amplitude: Float = 0.15f
    private var phase: Float = 0f

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }

    private val animator = ValueAnimator.ofFloat(0f, 2 * Math.PI.toFloat()).apply {
        duration = 1200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        animator.start()
    }

    fun setMode(mode: Mode) {
        if (currentMode != mode) {
            currentMode = mode
            invalidate()
        }
    }

    fun setRms(rms: Double) {
        // Normalize RMS (~20 to ~2000) to 0.15 .. 1.0f
        val target = ((rms - 30.0) / 400.0).coerceIn(0.15, 1.0).toFloat()
        amplitude = amplitude * 0.7f + target * 0.3f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2f
        val numBars = 28
        val barWidth = (width / (numBars * 1.8f)).coerceAtLeast(6f)
        val spacing = barWidth * 0.8f
        val totalWidth = numBars * barWidth + (numBars - 1) * spacing
        val startX = (width - totalWidth) / 2f

        // Color based on active mode
        barPaint.color = when (currentMode) {
            Mode.IDLE -> Color.parseColor("#94A3B8") // Muted Slate
            Mode.SPEAKING_LOCAL -> Color.parseColor("#10B981") // Emerald Green
            Mode.SPEAKING_REMOTE -> Color.parseColor("#0284C7") // Sky Blue
            Mode.BARGE_IN -> Color.parseColor("#EF4444") // Coral Red
        }

        for (i in 0 until numBars) {
            val progress = i.toFloat() / numBars.toFloat()
            // Sine modulation based on phase and bar index
            val wave = sin((progress * Math.PI * 3 + phase).toDouble()).toFloat()
            val centerFalloff = 1f - Math.abs(progress - 0.5f) * 1.4f

            val baseHeight = when (currentMode) {
                Mode.IDLE -> 8f + 6f * wave
                Mode.SPEAKING_LOCAL -> (height * 0.75f * amplitude * (0.3f + 0.7f * centerFalloff) * (0.6f + 0.4f * wave)).coerceAtLeast(8f)
                Mode.SPEAKING_REMOTE -> (height * 0.65f * (0.4f + 0.6f * centerFalloff) * (0.5f + 0.5f * wave)).coerceAtLeast(10f)
                Mode.BARGE_IN -> (height * 0.85f * (0.5f + 0.5f * centerFalloff) * (0.7f + 0.3f * wave)).coerceAtLeast(12f)
            }

            val x = startX + i * (barWidth + spacing)
            val halfH = baseHeight / 2f
            canvas.drawRoundRect(
                x,
                centerY - halfH,
                x + barWidth,
                centerY + halfH,
                barWidth / 2f,
                barWidth / 2f,
                barPaint
            )
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}
