package ir.solina.fm

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource

/**
 * Shared Material 3 color system for Compose screens.
 *
 * Colors come from values/colors.xml and values-night/colors.xml so Compose
 * screens stay aligned with the native Material theme used by the rest of the app.
 */
@Composable
fun SolinaComposeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(
            primary = colorResource(R.color.solina_primary),
            onPrimary = colorResource(R.color.solina_on_primary),
            primaryContainer = colorResource(R.color.solina_primary_container),
            onPrimaryContainer = colorResource(R.color.solina_on_primary_container),
            secondary = colorResource(R.color.solina_secondary),
            onSecondary = colorResource(R.color.solina_on_secondary),
            secondaryContainer = colorResource(R.color.solina_secondary_container),
            onSecondaryContainer = colorResource(R.color.solina_on_secondary_container),
            tertiary = colorResource(R.color.solina_tertiary),
            onTertiary = colorResource(R.color.solina_on_tertiary),
            tertiaryContainer = colorResource(R.color.solina_tertiary_container),
            onTertiaryContainer = colorResource(R.color.solina_on_tertiary_container),
            background = colorResource(R.color.solina_background),
            surface = colorResource(R.color.solina_surface),
            onSurface = colorResource(R.color.solina_on_surface),
            surfaceVariant = colorResource(R.color.solina_surface_variant),
            onSurfaceVariant = colorResource(R.color.solina_on_surface_variant),
            error = colorResource(R.color.solina_error),
            outline = colorResource(R.color.solina_outline)
        )
    } else {
        lightColorScheme(
            primary = colorResource(R.color.solina_primary),
            onPrimary = colorResource(R.color.solina_on_primary),
            primaryContainer = colorResource(R.color.solina_primary_container),
            onPrimaryContainer = colorResource(R.color.solina_on_primary_container),
            secondary = colorResource(R.color.solina_secondary),
            onSecondary = colorResource(R.color.solina_on_secondary),
            secondaryContainer = colorResource(R.color.solina_secondary_container),
            onSecondaryContainer = colorResource(R.color.solina_on_secondary_container),
            tertiary = colorResource(R.color.solina_tertiary),
            onTertiary = colorResource(R.color.solina_on_tertiary),
            tertiaryContainer = colorResource(R.color.solina_tertiary_container),
            onTertiaryContainer = colorResource(R.color.solina_on_tertiary_container),
            background = colorResource(R.color.solina_background),
            surface = colorResource(R.color.solina_surface),
            onSurface = colorResource(R.color.solina_on_surface),
            surfaceVariant = colorResource(R.color.solina_surface_variant),
            onSurfaceVariant = colorResource(R.color.solina_on_surface_variant),
            error = colorResource(R.color.solina_error),
            outline = colorResource(R.color.solina_outline)
        )
    }

    MaterialTheme(colorScheme = scheme, content = content)
}
