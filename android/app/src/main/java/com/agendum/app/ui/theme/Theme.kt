package com.agendum.app.ui.theme

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Immutable
data class AgendumExtendedColors(
    val isDark: Boolean,
    val warning: Color,
    val warningInk: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val cardSurface: Color,
    val subtleSurface: Color,
    val canvasTopGradient: Color,
    val canvasBottom: Color,
    val glassHeaderBackground: Color,
    val glassHeaderBorder: Color,
    val traySurface: Color,
    val trayBorder: Color,
    val springBud: Color,
    val cardShadowAmbient: Color,
    val cardTopHighlight: Color,
    val categoryAcademic: Color,
    val categoryUrgent: Color,
    val categoryInfo: Color,
    val categoryAction: Color,
) {
    val canvasBrush: Brush
        get() = Brush.verticalGradient(
            0.0f to canvasTopGradient,
            0.38f to canvasBottom,
            1.0f to canvasBottom,
        )
}

private val LightExtendedColors = AgendumExtendedColors(
    isDark = false,
    warning = Color(0xFFB26A00),
    warningInk = Color(0xFF8C5200),
    warningContainer = Color(0xFFFEF4DF),
    onWarningContainer = Color(0xFF8C5200),
    cardSurface = Color(0xFFFFFFFF),
    subtleSurface = Color(0xFFEDF6F1),
    canvasTopGradient = Color(0xFFE9F4EE),
    canvasBottom = Color(0xFFF3F8F5),
    glassHeaderBackground = Color(0xD9E9F4EE),
    glassHeaderBorder = Color(0x1812805C),
    traySurface = Color(0xE8DFF0E7),
    trayBorder = Color(0x1C12805C),
    springBud = Color(0xFF22A878),
    cardShadowAmbient = Color(0x1A0F4A38),
    cardTopHighlight = Color.Transparent,
    categoryAcademic = Color(0xFF217D68),
    categoryUrgent = Color(0xFFD13B3B),
    categoryInfo = Color(0xFF2E6496),
    categoryAction = Color(0xFF22A878),
)

private val DarkExtendedColors = AgendumExtendedColors(
    isDark = true,
    warning = Color(0xFFF4C16C),
    warningInk = Color(0xFFF4C16C),
    warningContainer = Color(0xFF332B1D),
    onWarningContainer = Color(0xFFF4C16C),
    cardSurface = Color(0xFF15221E),
    subtleSurface = Color(0xFF1A2B25),
    canvasTopGradient = Color(0xFF11221C),
    canvasBottom = Color(0xFF0D1614),
    glassHeaderBackground = Color(0xDC11221C),
    glassHeaderBorder = Color(0x14FFFFFF),
    traySurface = Color(0xEB162B23),
    trayBorder = Color(0x244AD8A2),
    springBud = Color(0xFF4AD8A2),
    cardShadowAmbient = Color(0x66000000),
    cardTopHighlight = Color(0x12FFFFFF),
    categoryAcademic = Color(0xFF62C3AA),
    categoryUrgent = Color(0xFFFF8A8A),
    categoryInfo = Color(0xFF78B1E6),
    categoryAction = Color(0xFF4AD8A2),
)

val LocalAgendumColors = staticCompositionLocalOf { LightExtendedColors }

val LocalReducedMotion = staticCompositionLocalOf { false }

fun TextStyle.tabularNums(): TextStyle = copy(fontFeatureSettings = "tnum")

fun isReducedMotionEnabled(context: Context): Boolean {
    return runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)
}

private val AgendumTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 15.5.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = TextStyle(
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 13.5.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
        fontFeatureSettings = "tnum",
    ),
    labelLarge = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    ),
    labelSmall = TextStyle(
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    ),
)

private val MistLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF12805C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9F2E6),
    onPrimaryContainer = Color(0xFF0B4D36),
    secondary = Color(0xFF22A878),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDFF0E7),
    onSecondaryContainer = Color(0xFF0F241D),
    background = Color(0xFFF3F8F5),
    onBackground = Color(0xFF0F241D),
    surface = Color(0xFFF3F8F5),
    onSurface = Color(0xFF0F241D),
    surfaceVariant = Color(0xFFDFF0E7),
    onSurfaceVariant = Color(0xFF4A655B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEDF6F1),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE6F3EC),
    surfaceContainerHighest = Color(0xFFD9ECE2),
    outline = Color(0xFF668076),
    outlineVariant = Color(0xFFDCE8E1),
    inverseSurface = Color(0xFF122820),
    inverseOnSurface = Color(0xFFF3F8F5),
    inversePrimary = Color(0xFF4AD8A2),
    error = Color(0xFFD13B3B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDECEC),
    onErrorContainer = Color(0xFF8F1D1D),
)

private val MistDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF4AD8A2),
    onPrimary = Color(0xFF06261B),
    primaryContainer = Color(0xFF16362B),
    onPrimaryContainer = Color(0xFFA8F0D2),
    secondary = Color(0xFF4AD8A2),
    onSecondary = Color(0xFF06261B),
    secondaryContainer = Color(0xFF162B23),
    onSecondaryContainer = Color(0xFFE6F4EE),
    background = Color(0xFF0D1614),
    onBackground = Color(0xFFE6F4EE),
    surface = Color(0xFF0D1614),
    onSurface = Color(0xFFE6F4EE),
    surfaceVariant = Color(0xFF162B23),
    onSurfaceVariant = Color(0xFF96B2A7),
    surfaceContainerLowest = Color(0xFF121D1A),
    surfaceContainerLow = Color(0xFF1A2B25),
    surfaceContainer = Color(0xFF15221E),
    surfaceContainerHigh = Color(0xFF1C2E28),
    surfaceContainerHighest = Color(0xFF223730),
    outline = Color(0xFF769387),
    outlineVariant = Color(0xFF233630),
    inverseSurface = Color(0xFFE6F4EE),
    inverseOnSurface = Color(0xFF0D1614),
    inversePrimary = Color(0xFF12805C),
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF4A0D0D),
    errorContainer = Color(0xFF3A2022),
    onErrorContainer = Color(0xFFFFD4D4),
)

/**
 * 青岚磁吸主题：默认锁定品牌清透青绿配色（dynamicColor = false），避免 Android 12+ 壁纸取色吞掉绿色主调。
 */
@Composable
fun AgendumTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> MistDarkColorScheme
        else -> MistLightColorScheme
    }

    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    var reducedMotion by remember { mutableStateOf(isReducedMotionEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reducedMotion = isReducedMotionEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    CompositionLocalProvider(
        LocalAgendumColors provides extendedColors,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AgendumTypography,
            content = content,
        )
    }
}
