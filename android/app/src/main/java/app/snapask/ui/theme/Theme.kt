package app.snapask.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 紙とインクを基調に、差し色は深い青緑の1色だけ。
// 端末の壁紙に合わせた配色(ダイナミックカラー)は使わず、どの端末でも同じ見た目にする。
private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5F5B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6EBE8),
    onPrimaryContainer = Color(0xFF0B2F2C),
    secondary = Color(0xFF5A635F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9EDE8),
    onSecondaryContainer = Color(0xFF1C1F1D),
    background = Color(0xFFFAFAF7),
    onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFFAFAF7),
    onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color(0xFFEFEEE9),
    onSurfaceVariant = Color(0xFF5E5C56),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F4F0),
    surfaceContainer = Color(0xFFF1F0EB),
    surfaceContainerHigh = Color(0xFFEBEAE5),
    surfaceContainerHighest = Color(0xFFE5E4DF),
    outline = Color(0xFF8A8880),
    outlineVariant = Color(0xFFDDDBD4),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD0C8),
    onPrimary = Color(0xFF00332F),
    primaryContainer = Color(0xFF1C4A46),
    onPrimaryContainer = Color(0xFFBDE9E3),
    secondary = Color(0xFFBEC8C3),
    onSecondary = Color(0xFF28322E),
    secondaryContainer = Color(0xFF2A302D),
    onSecondaryContainer = Color(0xFFE2E6E2),
    background = Color(0xFF121413),
    onBackground = Color(0xFFE6E4DE),
    surface = Color(0xFF121413),
    onSurface = Color(0xFFE6E4DE),
    surfaceVariant = Color(0xFF2A2C2A),
    onSurfaceVariant = Color(0xFFB4B2AA),
    surfaceContainerLowest = Color(0xFF0D0F0E),
    surfaceContainerLow = Color(0xFF191C1A),
    surfaceContainer = Color(0xFF1D201E),
    surfaceContainerHigh = Color(0xFF272A28),
    surfaceContainerHighest = Color(0xFF323533),
    outline = Color(0xFF8E8C85),
    outlineVariant = Color(0xFF3A3C39),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

// 説明を読むことが中心のアプリなので、本文の行間を広めにとる。
private val Base = Typography()
private val AppTypography = Base.copy(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, lineHeight = 34.sp),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    bodyLarge = Base.bodyLarge.copy(lineHeight = 28.sp, letterSpacing = 0.2.sp),
    bodyMedium = Base.bodyMedium.copy(lineHeight = 22.sp),
)

@Composable
fun SnapAskTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
