package com.flux.tv

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.max

/**
 * Base de tous les écrans : l'interface est dessinée pour une largeur de 960 « points ».
 * Les boîtiers Android génériques (X96Q, etc.) annoncent souvent une densité d'écran
 * fantaisiste qui rendrait tout minuscule ou énorme : on la recalcule depuis la vraie
 * largeur de l'écran pour que l'interface ait toujours la même taille.
 */
open class FluxActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val dm = newBase.resources.displayMetrics
        val widthPx = max(dm.widthPixels, dm.heightPixels)
        if (widthPx <= 0) {
            super.attachBaseContext(newBase)
            return
        }
        val config = Configuration(newBase.resources.configuration).apply {
            densityDpi = (widthPx * 160 / DESIGN_WIDTH_DP).coerceAtLeast(120)
            fontScale = 1f
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    /** Plein écran : on masque les barres de type téléphone que montrent certains boîtiers. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val DESIGN_WIDTH_DP = 960
    }
}
