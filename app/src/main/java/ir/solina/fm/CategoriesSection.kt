package ir.solina.fm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Reusable two-column category grid for screens where the grid owns the vertical scroll.
 *
 * Do not place this unbounded LazyVerticalGrid inside another vertically scrolling container
 * (such as LazyColumn). For a parent LazyColumn, emit category rows as parent items instead.
 */
@Composable
fun CategoriesGrid(
    items: List<CategoryItem>,
    modifier: Modifier = Modifier,
    columns: Int = 2
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns.coerceAtLeast(1)),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        items(items, key = { item -> item.id }) { item ->
            ModernCategoryCard(item = item)
        }
    }
}
