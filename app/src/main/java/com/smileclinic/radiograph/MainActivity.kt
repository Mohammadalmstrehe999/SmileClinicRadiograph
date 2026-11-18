package com.smileclinic.radiograph

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val clinicDarkColors = darkColorScheme(
            primary = Color(0xFF00E5FF),
            onPrimary = Color(0xFF00363D),
            secondary = Color(0xFFFFD600),
            surface = Color(0xFF1A1C1E),
            background = Color(0xFF121212)
        )
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = clinicDarkColors) {
                    RadiographMeasureScreen()
                }
            }
        }
    }
}
