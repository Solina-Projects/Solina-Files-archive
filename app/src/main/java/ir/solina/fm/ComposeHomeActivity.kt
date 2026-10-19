package ir.solina.fm

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class ComposeHomeActivity : AppCompatActivity() {
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

private data class CategoryStats(val count: Int = 0, val bytes: Long = 0L)
private data class HomeStats(
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val fileCount: Int = 0,
    val folderCount: Int = 0,
    val categories: Map<String, CategoryStats> = emptyMap()
)

private val categoryImageExtensionsHome = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "tif", "tiff", "svg", "avif", "raw", "dng")
private val categoryVideoExtensionsHome = setOf("mp4", "mkv", "mov", "webm", "3gp", "avi", "mpeg", "mpg", "m4v", "wmv", "flv", "ts", "mts")
private val categoryAudioExtensionsHome = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "flac", "opus", "mid", "midi", "wma", "amr", "aiff")
private val categoryArchiveExtensionsHome = setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "zst", "tgz", "tbz2", "iso", "cab", "jar", "war")
private val categoryDocumentExtensionsHome = setOf("pdf", "doc", "docx", "docm", "odt", "rtf", "txt", "text", "md", "markdown", "log", "xls", "xlsx", "xlsm", "ods", "csv", "ppt", "pptx", "pptm", "odp", "html", "htm", "xml", "json", "yaml", "yml", "epub", "pages", "numbers", "key")

private fun persianDigits(value: String): String = value.map { character ->
    if (character in '0'..'9') ('۰'.code + (character - '0')).toChar() else character
}.joinToString("")

private fun scanHomeStats(context: android.content.Context): HomeStats {
    val manager = context.getSystemService(StorageManager::class.java)
    val roots = try {
        manager.storageVolumes.mapNotNull { volume ->
            if (volume.state == Environment.MEDIA_MOUNTED || volume.state == Environment.MEDIA_MOUNTED_READ_ONLY)
                volume.directory?.takeIf { it.exists() && it.isDirectory }
            else null
        }.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
    } catch (_: Exception) {
        listOf(Environment.getExternalStorageDirectory())
    }.ifEmpty { listOf(Environment.getExternalStorageDirectory()) }

    var totalBytes = 0L
    var freeBytes = 0L
    roots.forEach { root ->
        runCatching {
            val stat = StatFs(root.path)
            totalBytes += stat.totalBytes
            freeBytes += stat.availableBytes
        }
    }
    val counts = mutableMapOf<String, Int>()
    val bytes = mutableMapOf<String, Long>()
    var fileCount = 0
    var folderCount = 0
    val visited = HashSet<String>()
    val stack = ArrayDeque<File>()
    roots.forEach { stack.addLast(it) }
    while (stack.isNotEmpty()) {
        val dir = stack.removeLast()
        val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        if (!visited.add(canonical)) continue
        val children = runCatching { dir.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
        for (child in children) {
            if (child.name.startsWith(".solina-trash-")) continue
            if (child.isDirectory) {
                folderCount++
                stack.addLast(child)
            } else if (child.isFile) {
                fileCount++
                val ext = child.extension.lowercase(Locale.ROOT)
                val matched = buildList {
                    add("recent")
                    if (ext in categoryImageExtensionsHome) add("images")
                    if (ext in categoryVideoExtensionsHome) add("videos")
                    if (ext in categoryAudioExtensionsHome) add("audio")
                    if (ext in categoryDocumentExtensionsHome || ext in categoryArchiveExtensionsHome) add("documents")
                    if (ext == "apk") add("apk")
                }
                matched.forEach { key ->
                    counts[key] = (counts[key] ?: 0) + 1
                    bytes[key] = (bytes[key] ?: 0L) + child.length()
                }
            }
        }
    }
    val categories = (counts.keys + bytes.keys).associateWith { CategoryStats(counts[it] ?: 0, bytes[it] ?: 0L) }
    return HomeStats(totalBytes, freeBytes, fileCount, folderCount, categories)
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
    var homeStats by remember { mutableStateOf(HomeStats()) }
    var statsLoading by remember { mutableStateOf(true) }
    var nightMode by remember {
        mutableStateOf((context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
    }
    LaunchedEffect(permissionRefreshKey) {
        val access = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (!access) {
            homeStats = HomeStats()
            statsLoading = false
        } else {
            statsLoading = true
            homeStats = withContext(Dispatchers.IO) { scanHomeStats(context) }
            statsLoading = false
        }
    }
    fun toggleHomeTheme() {
        val next = if (nightMode) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        context.getSharedPreferences("solina_preferences", android.content.Context.MODE_PRIVATE)
            .edit().putInt("night_mode", next).apply()
        nightMode = !nightMode
        AppCompatDelegate.setDefaultNightMode(next)
    }
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
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { toggleHomeTheme() },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    ) {
                        androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(if (nightMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                                    contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(if (nightMode) "تم روشن" else "تم تاریک", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.weight(1f))
                                Text("تغییر پوسته", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
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
                        QuickTool("پاک‌سازی", Icons.Default.CleaningServices, Modifier.weight(1f), onCleanTools)
                        QuickTool("انتقال", Icons.Default.Wifi, Modifier.weight(1f), onShare)
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
                    val usedBytes = (homeStats.totalBytes - homeStats.freeBytes).coerceAtLeast(0L)
                    val usedFraction = if (homeStats.totalBytes > 0L) {
                        (usedBytes.toFloat() / homeStats.totalBytes.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (hasAccess) onBrowse() else onRequestPermission()
                        },
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(58.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Storage,
                                            contentDescription = "حافظه دستگاه",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(34.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("حافظه دستگاه", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    Text(
                                        when {
                                            !hasAccess -> "برای مشاهده آمار، دسترسی لازم است"
                                            statsLoading -> "در حال محاسبه اطلاعات حافظه…"
                                            homeStats.totalBytes > 0L -> "استفاده‌شده: ${persianDigits((usedFraction * 100).toInt().toString())}٪"
                                            else -> "اطلاعات حافظه در دسترس نیست"
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                                TextButton(onClick = {
                                    if (hasAccess) onBrowse() else onRequestPermission()
                                }) { Text(if (hasAccess) "باز کردن" else "دسترسی") }
                            }
                            if (hasAccess && !statsLoading && homeStats.totalBytes > 0L) {
                                LinearProgressIndicator(
                                    progress = { usedFraction },
                                    modifier = Modifier.fillMaxWidth().height(8.dp),
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    StorageMetric(
                                        title = "استفاده‌شده",
                                        value = persianDigits(android.text.format.Formatter.formatFileSize(context, usedBytes)),
                                        modifier = Modifier.weight(1f)
                                    )
                                    StorageMetric(
                                        title = "فضای خالی",
                                        value = persianDigits(android.text.format.Formatter.formatFileSize(context, homeStats.freeBytes)),
                                        modifier = Modifier.weight(1f)
                                    )
                                    StorageMetric(
                                        title = "ظرفیت کل",
                                        value = persianDigits(android.text.format.Formatter.formatFileSize(context, homeStats.totalBytes)),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Folder, contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("${persianDigits(homeStats.folderCount.toString())} پوشه", fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(14.dp))
                                        Icon(Icons.Default.Description, contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("${persianDigits(homeStats.fileCount.toString())} فایل", fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
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
                                count = homeStats.categories[category.filter ?: "recent"]?.count ?: 0,
                                bytes = homeStats.categories[category.filter ?: "recent"]?.bytes ?: 0L,
                                statsLoading = statsLoading,
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
private fun StorageMetric(title: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            title,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CategoryTile(
    category: HomeCategory,
    count: Int,
    bytes: Long,
    statsLoading: Boolean,
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
                Text(category.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(category.subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 12.sp)
                Text(
                    if (statsLoading) "در حال شمارش…"
                    else "$count فایل • ${android.text.format.Formatter.formatFileSize(LocalContext.current, bytes)}",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 11.sp
                )
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
        Text(label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CompositionLocalProviderCompatRtl(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        content = content
    )
}
