package ir.solina.fm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Material You category/tool card with a themed icon tile, readable text, and RTL direction cue.
 * If no accent is supplied, the icon tile uses the theme's primary container colors.
 */
@Composable
fun ModernCategoryCard(
    item: CategoryItem,
    modifier: Modifier = Modifier
) {
    val iconBackgroundColor = item.accentColor?.let { Color(it) }
        ?: MaterialTheme.colorScheme.primaryContainer
    val iconTintColor = if (item.accentColor != null) Color.White
        else MaterialTheme.colorScheme.onPrimaryContainer

    ElevatedCard(
        onClick = item.onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 90.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = iconBackgroundColor,
                    shape = MaterialTheme.shapes.small,
                    tonalElevation = 2.dp
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = iconTintColor,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(1.dp))
            Icon(
                imageVector = Icons.Default.ChevronLeft,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** Backwards-compatible name for existing callers. Prefer [ModernCategoryCard]. */
@Composable
fun CategoryCard(
    item: CategoryItem,
    modifier: Modifier = Modifier
) = ModernCategoryCard(item = item, modifier = modifier)
