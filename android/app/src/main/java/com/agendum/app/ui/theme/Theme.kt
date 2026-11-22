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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.agendum.app.R

@Immutable
data class AgendumExtendedColors(
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val cardSurface: Color,
    val subtleSurface: Color,
)

val LocalAgendumColors = staticCompositionLocalOf {
    AgendumExtendedColors(
        warning = Color(0xFF74500F),
        warningContainer = Color(0xFFFFF0CE),
        onWarningContainer = Color(0xFF74500F),
        cardSurface = Color.White,
        subtleSurface = Color(0xFFE6EEE7),
    )
}

val LocalReducedMotion = staticCompositionLocalOf { false }

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
        fontSize = 26.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Bold,
    ),
    titleLarge = TextStyle(
        fontSize = 21.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
)

@Composable
private fun fallbackLightColorScheme(): ColorScheme = lightColorScheme(
    primary = colorResource(R.color.primary),
    onPrimary = colorResource(R.color.on_primary),
    primaryContainer = Color(0xFFDCECDF),
    onPrimaryContainer = colorResource(R.color.ink),
    secondary = colorResource(R.color.primary),
    onSecondary = colorResource(R.color.on_primary),
    secondaryContainer = Color(0xFFE6EEE7),
    onSecondaryContainer = colorResource(R.color.ink),
    background = colorResource(R.color.surface),
    onBackground = colorResource(R.color.ink),
    surface = colorResource(R.color.surface),
    onSurface = colorResource(R.color.ink),
    surfaceVariant = Color(0xFFE2EAE2),
    onSurfaceVariant = colorResource(R.color.muted),
    surfaceContainerLowest = colorResource(R.color.card),
    surfaceContainerLow = colorResource(R.color.card),
    surfaceContainer = colorResource(R.color.card),
    surfaceContainerHigh = Color(0xFFEBF1EB),
    surfaceContainerHighest = Color(0xFFE2EAE2),
    outline = Color(0xFF73877A),
    outlineVariant = Color(0xFFB0BEB3),
    error = Color(0xFFA5312D),
    onError = Color.White,
    errorContainer = Color(0xFFFFEDEB),
    onErrorContainer = Color(0xFF6E1412),
)

@Composable
private fun fallbackDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = colorResource(R.color.primary),
    onPrimary = colorResource(R.color.on_primary),
    primaryContainer = Color(0xFF304D39),
    onPrimaryContainer = colorResource(R.color.ink),
    secondary = colorResource(R.color.primary),
    onSecondary = colorResource(R.color.on_primary),
    secondaryContainer = Color(0xFF2B3F31),
    onSecondaryContainer = colorResource(R.color.ink),
    background = colorResource(R.color.surface),
    onBackground = colorResource(R.color.ink),
    surface = colorResource(R.color.surface),
    onSurface = colorResource(R.color.ink),
    surfaceVariant = Color(0xFF2B342E),
    onSurfaceVariant = colorResource(R.color.muted),
    surfaceContainerLowest = colorResource(R.color.card),
    surfaceContainerLow = colorResource(R.color.card),
    surfaceContainer = colorResource(R.color.card),
    surfaceContainerHigh = Color(0xFF2B342E),
    surfaceContainerHighest = Color(0xFF343F38),
    outline = Color(0xFF91A397),
    outlineVariant = Color(0xFF536459),
    error = Color(0xFFFFB4AC),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF502925),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun AgendumTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> fallbackDarkColorScheme()
        else -> fallbackLightColorScheme()
    }

    val extendedColors = if (darkTheme) {
        AgendumExtendedColors(
            warning = colorResource(R.color.warning),
            warningContainer = Color(0xFF373022),
            onWarningContainer = colorResource(R.color.warning),
            cardSurface = colorScheme.surfaceContainer,
            subtleSurface = colorScheme.primaryContainer.copy(alpha = 0.55f),
        )
    } else {
        AgendumExtendedColors(
            warning = colorResource(R.color.warning),
            warningContainer = Color(0xFFFFF0CE),
            onWarningContainer = colorResource(R.color.warning),
            cardSurface = colorScheme.surfaceContainerLowest,
            subtleSurface = colorScheme.primaryContainer.copy(alpha = 0.55f),
        )
    }

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
