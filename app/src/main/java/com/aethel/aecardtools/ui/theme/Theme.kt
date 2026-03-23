/*
 * Copyright (C) 2025-2026  Aethel-Systems
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.aethel.aecardtools.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 主题模式枚举 (符合 c.md 第四章)
 */
enum class ThemeMode {
    GRASS_GREEN,  // 标准模式（草绿色）
    PALE_BLUE,    // AEFS 专用模式（淡蓝色）
    DYNAMIC       // 动态取色（Android 12+）
}

/**
 * 草绿色深色主题
 */
private val DarkColorSchemeGrassGreen = darkColorScheme(
    primary = Primary_GrassGreen,
    onPrimary = OnPrimary_GrassGreen,
    primaryContainer = PrimaryContainer_GrassGreen,
    onPrimaryContainer = OnPrimaryContainer_GrassGreen,
    
    secondary = Secondary_GrassGreen,
    onSecondary = OnSecondary_GrassGreen,
    secondaryContainer = SecondaryContainer_GrassGreen,
    onSecondaryContainer = OnSecondaryContainer_GrassGreen,
    
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    
    error = Error,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer
)

/**
 * 草绿色浅色主题
 */
private val LightColorSchemeGrassGreen = lightColorScheme(
    primary = Primary_GrassGreen,
    onPrimary = OnPrimary_GrassGreen,
    primaryContainer = PrimaryContainer_GrassGreen,
    onPrimaryContainer = OnPrimaryContainer_GrassGreen,
    
    secondary = Secondary_GrassGreen,
    onSecondary = OnSecondary_GrassGreen,
    secondaryContainer = SecondaryContainer_GrassGreen,
    onSecondaryContainer = OnSecondaryContainer_GrassGreen,
    
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF757575),
    
    error = Error,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer
)

/**
 * 淡蓝色深色主题
 */
private val DarkColorSchemePaleBlue = darkColorScheme(
    primary = Primary_PaleBlue,
    onPrimary = OnPrimary_PaleBlue,
    primaryContainer = PrimaryContainer_PaleBlue,
    onPrimaryContainer = OnPrimaryContainer_PaleBlue,
    
    secondary = Secondary_PaleBlue,
    onSecondary = OnSecondary_PaleBlue,
    secondaryContainer = SecondaryContainer_PaleBlue,
    onSecondaryContainer = OnSecondaryContainer_PaleBlue,
    
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    
    error = Error,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer
)

/**
 * 淡蓝色浅色主题
 */
private val LightColorSchemePaleBlue = lightColorScheme(
    primary = Primary_PaleBlue,
    onPrimary = OnPrimary_PaleBlue,
    primaryContainer = PrimaryContainer_PaleBlue,
    onPrimaryContainer = OnPrimaryContainer_PaleBlue,
    
    secondary = Secondary_PaleBlue,
    onSecondary = OnSecondary_PaleBlue,
    secondaryContainer = SecondaryContainer_PaleBlue,
    onSecondaryContainer = OnSecondaryContainer_PaleBlue,
    
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF757575),
    
    error = Error,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer
)

/**
 * AECardTools 主题函数
 * 支持草绿色、淡蓝色和动态取色三种模式
 */
@Composable
fun AECardToolsTheme(
    themeMode: ThemeMode = ThemeMode.GRASS_GREEN,
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> {
            when (themeMode) {
                ThemeMode.GRASS_GREEN -> DarkColorSchemeGrassGreen
                ThemeMode.PALE_BLUE -> DarkColorSchemePaleBlue
                ThemeMode.DYNAMIC -> DarkColorSchemeGrassGreen
            }
        }
        else -> {
            when (themeMode) {
                ThemeMode.GRASS_GREEN -> LightColorSchemeGrassGreen
                ThemeMode.PALE_BLUE -> LightColorSchemePaleBlue
                ThemeMode.DYNAMIC -> LightColorSchemeGrassGreen
            }
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.copy(alpha = 0.95f).hashCode()
            WindowCompat.getInsetsController(window, view)?.isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AECardToolsTypography,
        content = content
    )
}