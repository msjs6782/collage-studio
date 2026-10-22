package com.example.collagestudio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Rose = Color(0xFFE5486B)

private val Light = lightColorScheme(
    primary = Rose,
    onPrimary = Color.White,
    secondary = Color(0xFF3A86FF),
    background = Color(0xFFFAF7F8),
    surface = Color(0xFFFFFFFF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFF7A95),
    onPrimary = Color(0xFF3B0012),
    secondary = Color(0xFF8AB4FF),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
