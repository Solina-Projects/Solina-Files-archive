package ir.solina.fm

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

private val SolinaBlue = Color(0xFF0B57D0)
private val SolinaBackground = Color(0xFFF7F8FC)
private val SolinaInk = Color(0xFF202124)
private val SolinaMuted = Color(0xFF5F6368)

class ComposeHomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val isDark = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            MaterialTheme(
                colorScheme = if (isDark) darkColorScheme(primary = Color(0xFF9DBDFF))
                else lightColorScheme(
                    primary = SolinaBlue,
                    background = SolinaBackground,
                    surface = Color.White,
                    onSurface = SolinaInk
                )
            ) {
                SolinaHomeScreen(
                    onBrowse = { openBrowser() },
                    onSearch = { query ->
                        startActivity(Intent(this, MainActivity::class.java)
                            .putExtra("search_query", query))
                    },
                    onOpenFolder = { path, category ->
                        val intent = Intent(this, MainActivity::class.java)
                            .putExtra("start_path", path)
                        if (!category.isNullOrBlank()) intent.putExtra("file_category", category)
                        startActivity(intent)
                    },
                    onOpenFile = { path ->
                        startActivity(Intent(this, MainActivity::class.java)
                            .putExtra("open_recent_path", path))
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
}

private data class HomeCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val tint: Color,
    val directory: String?,
    val filter: String? = null
)

private fun categoryDirectory(name: String?): File? {
    if (name.isNullOrBlank()) return null
    return Environment.getExternalStoragePublicDirectory(name)
}

private fun scanHomeFiles(): List<File> {
    val root = Environment.getExternalStorageDirectory()
    val dirs = listOf(
        Environment.DIRECTORY_DOWNLOADS,
        Environment.DIRECTORY_DOCUMENTS,
        Environment.DIRECTORY_PICTURES,
        Environment.DIRECTORY_MOVIES,
        Environment.DIRECTORY_MUSIC
    ).mapNotNull { categoryDirectory(it) }.filter { it.exists() && it.isDirectory }
    val candidates = (dirs.flatMap { it.listFiles()?.toList().orEmpty() } +
        root.listFiles()?.toList().orEmpty())
        .filter { it.isFile && !it.name.startsWith(".") }
        .distinctBy { it.absolutePath }
    return candidates.sortedByDescending { it.lastModified() }.take(8)
}

private fun formatSize(context: android.content.Context, bytes: Long): String =
    Formatter.formatFileSize(context, bytes)

@Composable
private fun SolinaHomeScreen(
    onBrowse: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenFolder: (String, String?) -> Unit,
    onOpenFile: (String) -> Unit,
    onDualPane: () -> Unit,
    onVault: () -> Unit,
    onShare: () -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    var recentFiles by remember { mutableStateOf(scanHomeFiles()) }
    val hasAccess = Environment.isExternalStorageManager()
    val root = Environment.getExternalStorageDirectory()
    val (total, free) = remember {
        try {
            val stats = StatFs(root.absolutePath)
            stats.totalBytes to stats.availableBytes
        } catch (_: Exception) {
            0L to 0L
        }
    }
    val used = (total - free).coerceAtLeast(0L)
    val progress = if (total > 0L) used.toFloat() / total.toFloat() else 0f
    val categories = listOf(
        HomeCategory("دانلودها", "فایل‌های دریافت‌شده", Icons.Default.FolderOpen,
            Color(0xFF1A73E8), Environment.DIRECTORY_DOWNLOADS),
        HomeCategory("تصاویر", "عکس‌ها و تصاویر", Icons.Default.Image,
            Color(0xFFAF52DE), Environment.DIRECTORY_PICTURES, "images"),
        HomeCategory("ویدیوها", "فیلم‌ها و کلیپ‌ها", Icons.Default.VideoLibrary,
            Color(0xFFE5484D), Environment.DIRECTORY_MOVIES, "videos"),
        HomeCategory("موسیقی", "فایل‌های صوتی", Icons.Default.MusicNote,
            Color(0xFFE58B22), Environment.DIRECTORY_MUSIC, "audio"),
        HomeCategory("اسناد", "PDF، متن و آفیس", Icons.Default.Description,
            Color(0xFF168A83), Environment.DIRECTORY_DOCUMENTS, "documents"),
        HomeCategory("فایل‌های APK", "نصب و بررسی برنامه", Icons.Default.Android,
            Color(0xFF27864B), null, "apk")
    )

    CompositionLocalProviderCompatRtl {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 18.dp, end = 18.dp,
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 10.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp
                ),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("مدیریت فایل‌ها", fontSize = 25.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(4.dp))
                            Text("همه‌چیز مرتب و در دسترس", fontSize = 13.sp, color = SolinaMuted)
                        }
                        Surface(shape = CircleShape, color = Color(0xFFE8F0FE), modifier = Modifier.size(46.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = SolinaBlue,
                                    modifier = Modifier.size(25.dp))
                            }
                        }
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
                        shape = RoundedCornerShape(22.dp),
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
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(26.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = RoundedCornerShape(13.dp), color = Color(0xFFE8F0FE)) {
                                    Icon(Icons.Default.Storage, contentDescription = null, tint = SolinaBlue,
                                        modifier = Modifier.padding(10.dp).size(22.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("حافظه دستگاه", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                    Text(if (hasAccess) "وضعیت فضای ذخیره‌سازی" else "برای مشاهده فایل‌ها دسترسی لازم است",
                                        color = SolinaMuted, fontSize = 12.sp)
                                }
                                TextButton(onClick = onBrowse) { Text("باز کردن") }
                            }
                            if (total > 0L) {
                                LinearProgressIndicator(
                                    progress = progress.coerceIn(0f, 1f),
                                    modifier = Modifier.fillMaxWidth().height(9.dp).clip(CircleShape),
                                    color = SolinaBlue,
                                    trackColor = Color(0xFFE5E7EB)
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text("استفاده‌شده", color = SolinaMuted, fontSize = 11.sp)
                                        Text(formatSize(context, used), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("فضای آزاد", color = SolinaMuted, fontSize = 11.sp)
                                        Text(formatSize(context, free), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                }
                            } else {
                                Text("اطلاعات حافظه در دسترس نیست", color = SolinaMuted, fontSize = 13.sp)
                            }
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("دسته‌بندی فایل‌ها", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = onBrowse) { Text("همه فایل‌ها") }
                    }
                }
                items(categories.chunked(2)) { rowCategories ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowCategories.forEach { category ->
                            CategoryTile(
                                category = category,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val dir = categoryDirectory(category.directory)
                                    val path = dir?.takeIf { it.exists() && it.isDirectory }?.absolutePath
                                        ?: root.absolutePath
                                    onOpenFolder(path, category.filter)
                                }
                            )
                        }
                        if (rowCategories.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("دسترسی سریع", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { recentFiles = scanHomeFiles() }) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("تازه‌سازی")
                        }
                    }
                }
                if (!hasAccess) {
                    item {
                        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text("برای دیدن فایل‌های واقعی، دسترسی حافظه را فعال کن.",
                                    color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = onBrowse) { Text("فعال‌سازی دسترسی") }
                            }
                        }
                    }
                } else if (recentFiles.isEmpty()) {
                    item { Text("فایل اخیری پیدا نشد.", color = SolinaMuted, fontSize = 13.sp) }
                } else {
                    items(recentFiles, key = { it.absolutePath }) { file ->
                        RecentFileRow(file = file, onClick = { onOpenFile(file.absolutePath) })
                    }
                }
                item {
                    Text("ابزارها", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuickTool("دو پنجره‌ای", Icons.Default.GridView, Modifier.weight(1f), onDualPane)
                        QuickTool("گاوصندوق", Icons.Default.Lock, Modifier.weight(1f), onVault)
                        QuickTool("انتقال فایل", Icons.Default.Share, Modifier.weight(1f), onShare)
                        QuickTool("تنظیمات", Icons.Default.Settings, Modifier.weight(1f), onSettings)
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
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(13.dp), color = category.tint.copy(alpha = 0.12f)) {
                Icon(category.icon, contentDescription = null, tint = category.tint,
                    modifier = Modifier.padding(9.dp).size(23.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(category.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(category.subtitle, fontSize = 10.sp, color = SolinaMuted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 13.sp)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = SolinaMuted,
                modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun RecentFileRow(file: File, onClick: () -> Unit) {
    val icon = when (file.extension.lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> Icons.Default.Image
        "mp4", "mkv", "mov", "webm", "3gp" -> Icons.Default.VideoLibrary
        "mp3", "m4a", "aac", "wav", "ogg", "flac", "opus" -> Icons.Default.MusicNote
        "apk" -> Icons.Default.Android
        "zip", "7z", "rar", "tar", "gz" -> Icons.Default.Archive
        "pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx" -> Icons.Default.Description
        else -> Icons.Default.Folder
    }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
            .clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFE8F0FE)) {
            Icon(icon, contentDescription = null, tint = SolinaBlue,
                modifier = Modifier.padding(10.dp).size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatSize(LocalContext.current, file.length()), fontSize = 12.sp, color = SolinaMuted)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = "باز کردن", tint = SolinaMuted)
    }
    Divider(color = Color(0xFFECEFF3))
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
        Icon(icon, contentDescription = null, tint = SolinaBlue, modifier = Modifier.size(22.dp))
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
