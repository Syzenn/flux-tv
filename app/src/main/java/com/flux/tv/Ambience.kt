package com.flux.tv

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.max

/**
 * Fond d'écran façon Apple TV : un dégradé sombre et une grande lueur colorée
 * qui prend doucement la couleur de la chaîne sélectionnée.
 */
class Ambience(base: View, private val glow: View, initial: Int = DEFAULT) {

    private val glowDrawable = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        setGradientCenter(0.5f, 0.38f)
    }
    private val baseDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.BLACK, Color.BLACK, Color.BLACK),
    )

    private var shown = initial
    private var target = initial
    private var animator: ValueAnimator? = null

    init {
        glow.background = glowDrawable
        base.background = baseDrawable
        glow.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            glowDrawable.gradientRadius = max(v.width, v.height) * 0.70f
        }
        paint(initial)
    }

    fun setColor(color: Int) {
        if (color == target) return
        target = color
        animator?.cancel()
        animator = ValueAnimator.ofObject(ArgbEvaluator(), shown, color).apply {
            duration = 550
            addUpdateListener { paint(it.animatedValue as Int) }
            start()
        }
    }

    fun cancel() {
        animator?.cancel()
    }

    private fun paint(c: Int) {
        shown = c
        glowDrawable.colors = intArrayOf(
            ColorUtils.setAlphaComponent(c, 225),
            ColorUtils.setAlphaComponent(c, 85),
            Color.TRANSPARENT,
        )
        baseDrawable.colors = intArrayOf(
            ColorUtils.blendARGB(c, Color.BLACK, 0.62f),
            ColorUtils.blendARGB(c, Color.BLACK, 0.90f),
            Color.parseColor("#050608"),
        )
    }

    companion object {
        val DEFAULT = Color.parseColor("#3A55C8")

        private val PALETTE = intArrayOf(
            Color.parseColor("#C2283A"), // rouge
            Color.parseColor("#2F5BD3"), // bleu
            Color.parseColor("#7A3FD1"), // violet
            Color.parseColor("#138A80"), // turquoise
            Color.parseColor("#D46A1F"), // orange
            Color.parseColor("#2E8B4E"), // vert
            Color.parseColor("#C23A84"), // rose
            Color.parseColor("#3D46B8"), // indigo
        )

        fun fallback(id: Int): Int = PALETTE[abs(id) % PALETTE.size]

        /** Rend une couleur de logo assez riche pour faire une belle lueur, ou null si elle est trop terne. */
        fun richen(color: Int): Int? {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            if (hsv[1] < 0.25f || hsv[2] < 0.18f) return null
            hsv[1] = max(hsv[1], 0.62f)
            hsv[2] = 0.80f
            return Color.HSVToColor(hsv)
        }
    }
}
