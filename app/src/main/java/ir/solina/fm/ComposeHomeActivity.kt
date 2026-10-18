package ir.solina.fm

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import java.io.File
import java.util.Locale

class ComposeHomeActivity : ComponentActivity() {
    private var resumeVersion by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeVersion++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SolinaComposeTheme {
                SolinaHomeScreen(
                    permissionRefreshKey = resumeVersion,
                    onBrowse = { openBrowser() },
                    onRequestPermission = { requestStoragePermission() },
                    onCleanTools = { openCleanTools() },
                    onSearch = { query ->
                        startActivity(Intent(this, MainActivity::class.java)
                            .putExtra("search_query", query))
                    },
                    onOpenCategory = { title, category ->
                        startActivity(Intent(this, HomeCategoryActivity::class.java)
                            .putExtra("category_title", title)
                            .putExtra("category_filter", category))
                    },
                    onDualPane = { startActivity(Intent(this, DualPaneActivity::class.java)) },
                    onVault = { startActivity(Intent(this, VaultActivity::class.java)) },
                    onShare = { startActivity(Intent(this, WifiTransferActivity::class.java)) },
                    onSettings = { startActivity(Intent(this, AdvancedSettingsActivity::class.java)) }
                )
            }
        }
    }

    private fun openBrowser() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun requestStoragePermission() {
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra("request_storage_permission", true))
    }

    private fun openCleanTools() {
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra("open_clean_tools", true))
    }
}

private data class HomeCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val tint: Color,
    val filter: String? = null
)

@Composable
private fun SolinaHomeScreen(
    permissionRefreshKey: Int,
    onBrowse: () -> Unit,
    onRequestPermission: () -> Unit,
    onCleanTools: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenCategory: (String, String) -> Unit,
    onDualPane: () -> Unit,
    onVault: () -> Unit,
    onShare: () -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    val hasAccess = remember(permissionRefreshKey) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
    val categories = listOf(
        HomeCategory("دانلودها", "همه فایل‌های جدید از سراسر حافظه", Icons.Default.FolderOpen,
            Color(0xFF1A73E8), "recent"),
        HomeCategory("تصاویر", "عکس‌ها و تصاویر", Icons.Default.Image,
            Color(0xFFAF52DE), "images"),
        HomeCategory("ویدیوها", "فیلم‌ها و کلیپ‌ها", Icons.Default.VideoLibrary,
            Color(0xFFE5484D), "videos"),
        HomeCategory("موسیقی", "فایل‌های صوتی", Icons.Default.MusicNote,
            Color(0xFFE58B22), "audio"),
        HomeCategory("اسناد", "PDF، متن و آفیس", Icons.Default.Description,
            Color(0xFF168A83), "documents"),
        HomeCategory("فایل‌های APK", "نصب و بررسی برنامه", Icons.Default.Android,
            Color(0xFF27864B), "apk")
    )

    CompositionLocalProviderCompatRtl {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp, end = 12.dp,
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 6.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 10.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("مدیریت فایل‌ها", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(2.dp))
                            Text("همه‌چیز مرتب و در دسترس", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(25.dp))
                            }
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ابزارها", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        QuickTool("دوپنجره", Icons.Default.GridView, Modifier.weight(1f), onDualPane)
                        QuickTool("پاک‌سازی", Icons.Default.Refresh, Modifier.weight(1f), onCleanTools)
                        QuickTool("انتقال", Icons.Default.Share, Modifier.weight(1f), onShare)
                        QuickTool("خزانه", Icons.Default.Lock, Modifier.weight(1f), onVault)
                        QuickTool("تنظیمات", Icons.Default.Settings, Modifier.weight(1f), onSettings)
                    }
                }
                item {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("جستجو در فایل‌ها و پوشه‌ها") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "جستجو") },
                        trailingIcon = {
                            TextButton(onClick = { onSearch(search.trim()) }) {
                                Text("بگرد")
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch(search.trim()) }),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (hasAccess) onBrowse() else onRequestPermission()
                        },
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(8.dp).size(20.dp))
                            }
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text("حافظه دستگاه", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(if (hasAccess) "مشاهده فایل‌ها و پوشه‌ها" else "برای مشاهده فایل‌ها دسترسی لازم است",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = {
                                if (hasAccess) onBrowse() else onRequestPermission()
                            }) { Text(if (hasAccess) "باز کردن" else "دسترسی") }
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("دسته‌بندی فایل‌ها", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = onBrowse) { Text("همه فایل‌ها") }
                    }
                }
                items(categories.chunked(2)) { rowCategories ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowCategories.forEach { category ->
                            CategoryTile(
                                category = category,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    onOpenCategory(category.title, category.filter ?: "recent")
                                }
                            )
                        }
                        if (rowCategories.size == 1) Spacer(Modifier.weight(1f))
                    }
                }

            }
        }
    }
}

@Composable
private fun CategoryTile(
    category: HomeCategory,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(13.dp), color = category.tint.copy(alpha = 0.12f)) {
                Icon(category.icon, contentDescription = null, tint = category.tint,
                    modifier = Modifier.padding(6.dp).size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(category.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(category.subtitle, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 11.sp)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun QuickTool(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick).padding(vertical = 13.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(label, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CompositionLocalProviderCompatRtl(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        content = content
    )
}
