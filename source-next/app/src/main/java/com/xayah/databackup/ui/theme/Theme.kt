package com.xayah.databackup.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.xayah.databackup.ui.theme.color.hct.Hct
import com.xayah.databackup.util.AppThemeMode
import com.xayah.databackup.util.AppThemeModeSetting
import com.xayah.databackup.util.DynamicColor
import com.xayah.databackup.util.readBoolean
import com.xayah.databackup.util.readEnum
import com.xayah.databackup.ui.theme.color.scheme.SchemeContent

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun DataBackupTheme(
    darkTheme: Boolean? = null,
    dynamicColor: Boolean? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val savedThemeMode by context.readEnum<AppThemeMode>(AppThemeModeSetting)
        .collectAsStateWithLifecycle(initialValue = AppThemeModeSetting.second)
    val savedDynamicColor by context.readBoolean(DynamicColor)
        .collectAsStateWithLifecycle(initialValue = DynamicColor.second)
    val effectiveDarkTheme = darkTheme ?: when (savedThemeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val effectiveDynamicColor = dynamicColor ?: savedDynamicColor

    val materialColorScheme = when {
        effectiveDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (effectiveDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        effectiveDarkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val colorScheme = materialColorScheme.copy(
        background = materialColorScheme.surfaceContainer,
        surface = materialColorScheme.surfaceContainer,
        surfaceContainer = materialColorScheme.surfaceBright,
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

@Immutable
class CustomColorScheme(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val surfaceContainer: Color,
)

private val GreenDarkColorScheme = SchemeContent(
    sourceColorHct = Hct.fromInt(GreenSource.toArgb()),
    isDark = true,
    contrastLevel = 0.0,
).let { scheme ->
    CustomColorScheme(
        primary = Color(scheme.primary),
        onPrimary = Color(scheme.onPrimary),
        primaryContainer = Color(scheme.primaryContainer),
        onPrimaryContainer = Color(scheme.onPrimaryContainer),
        surfaceContainer = Color(scheme.surfaceContainer),
    )
}

private val GreenLightColorScheme = SchemeContent(
    sourceColorHct = Hct.fromInt(GreenSource.toArgb()),
    isDark = false,
    contrastLevel = 0.0,
).let { scheme ->
    CustomColorScheme(
        primary = Color(scheme.primary),
        onPrimary = Color(scheme.onPrimary),
        primaryContainer = Color(scheme.primaryContainer),
        onPrimaryContainer = Color(scheme.onPrimaryContainer),
        surfaceContainer = Color(scheme.surfaceContainer),
    )
}

object DataBackupTheme {
    val greenColorScheme: CustomColorScheme
        @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) {
            GreenDarkColorScheme
        } else {
            GreenLightColorScheme
        }
}
