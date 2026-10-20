package ir.solina.fm

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModelProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.res.painterResource
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
    private lateinit var homeViewModel: HomeViewModel

    override fun onResume() {
        super.onResume()
        resumeVersion++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        homeViewModel = ViewModelProvider(this)[HomeViewModel::class.java]
        setContent {
            SolinaComposeTheme {
                SolinaHomeScreen(
                    permissionRefreshKey = resumeVersion,
                    storageViewModel = homeViewModel,
                    onManualRefresh = { homeViewModel.refreshStorageStats() },
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
                    onSettings = { startActivity(Intent(this, AdvancedSettingsActivity::class.java)) },
                    onOpenFile = { path ->
                        startActivity(Intent(this, MainActivity::class.java).putExtra("open_recent_path", path))
                    }
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
private const val HOME_STATS_CACHE_PREFS = "solina_home_stats_cache"
private const val HOME_STATS_CACHE_TIME = "last_scan_at"
private const val HOME_STATS_CACHE_TTL_MS = 24L * 60L * 60L * 1000L

private data class HomeStats(
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val fileCount: Int = 0,
    val folderCount: Int = 0,
    val categories: Map<String, CategoryStats> = emptyMap(),
    val recentFiles: List<File> = emptyList()
)

private val categoryImageExtensionsHome = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "tif", "tiff", "svg", "avif", "raw", "dng")
private val categoryVideoExtensionsHome = setOf("mp4", "mkv", "mov", "webm", "3gp", "avi", "mpeg", "mpg", "m4v", "wmv", "flv", "ts", "mts")
private val categoryAudioExtensionsHome = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "flac", "opus", "mid", "midi", "wma", "amr", "aiff")
private val categoryArchiveExtensionsHome = setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "zst", "tgz", "tbz2", "iso", "cab", "jar", "war")
private val categoryDocumentExtensionsHome = setOf("pdf", "doc", "docx", "docm", "odt", "rtf", "txt", "text", "md", "markdown", "log", "xls", "xlsx", "xlsm", "ods", "csv", "ppt", "pptx", "pptm", "odp", "html", "htm", "xml", "json", "yaml", "yml", "epub", "pages", "numbers", "key")

internal fun persianDigits(value: String): String = value.map { character ->
    if (character in '0'..'9') ('۰'.code + (character - '0')).toChar() else character
}.joinToString("")


private fun readCachedHomeStats(context: android.content.Context): Pair<HomeStats, Long> {
    val prefs = context.getSharedPreferences(HOME_STATS_CACHE_PREFS, android.content.Context.MODE_PRIVATE)
    val timestamp = prefs.getLong(HOME_STATS_CACHE_TIME, 0L)
    val categories = listOf("recent", "images", "videos", "audio", "documents", "apk").associateWith { key ->
        CategoryStats(prefs.getInt("count_$key", 0), prefs.getLong("bytes_$key", 0L))
    }
    val paths = prefs.getStringSet("recent_paths", emptySet()).orEmpty()
    val recentFiles = paths.mapNotNull { path -> runCatching { File(path).takeIf { it.isFile } }.getOrNull() }
        .distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }.take(5)
    val stats = HomeStats(
        totalBytes = prefs.getLong("total_bytes", 0L),
        freeBytes = prefs.getLong("free_bytes", 0L),
        fileCount = prefs.getInt("file_count", 0),
        folderCount = prefs.getInt("folder_count", 0),
        categories = categories,
        recentFiles = recentFiles
    )
    return stats to timestamp
}

private fun saveCachedHomeStats(context: android.content.Context, stats: HomeStats, timestamp: Long) {
    val prefs = context.getSharedPreferences(HOME_STATS_CACHE_PREFS, android.content.Context.MODE_PRIVATE)
    val edit = prefs.edit()
        .putLong(HOME_STATS_CACHE_TIME, timestamp)
        .putLong("total_bytes", stats.totalBytes)
        .putLong("free_bytes", stats.freeBytes)
        .putInt("file_count", stats.fileCount)
        .putInt("folder_count", stats.folderCount)
        .putStringSet("recent_paths", stats.recentFiles.map { it.absolutePath }.toSet())
    listOf("recent", "images", "videos", "audio", "documents", "apk").forEach { key ->
        val category = stats.categories[key] ?: CategoryStats()
        edit.putInt("count_$key", category.count)
        edit.putLong("bytes_$key", category.bytes)
    }
    edit.apply()
}

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
    val recentFiles = ArrayList<File>()
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
                recentFiles.add(child)
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
    val latestFiles = recentFiles.distinctBy { it.absolutePath }
        .sortedByDescending { it.lastModified() }
        .take(5)
    return HomeStats(totalBytes, freeBytes, fileCount, folderCount, categories, latestFiles)
}

private data class HomeCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val filter: String? = null
)

@Composable
private fun SolinaHomeScreen(
    permissionRefreshKey: Int,
    storageViewModel: HomeViewModel,
    onManualRefresh: () -> Unit,
    onBrowse: () -> Unit,
    onRequestPermission: () -> Unit,
    onCleanTools: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenCategory: (String, String) -> Unit,
    onDualPane: () -> Unit,
    onVault: () -> Unit,
    onShare: () -> Unit,
    onSettings: () -> Unit,
    onOpenFile: (String) -> Unit
) {
    val context = LocalContext.current
    val viewModelStorageInfo by storageViewModel.storageInfo.collectAsState()
    val viewModelStorageLoading by storageViewModel.isLoading.collectAsState()
    var search by remember { mutableStateOf("") }
    var homeStats by remember { mutableStateOf(HomeStats()) }
    var statsLoading by remember { mutableStateOf(true) }
    var manualRefreshKey by remember { mutableIntStateOf(0) }
    var lastManualRefreshHandled by remember { mutableIntStateOf(0) }
    var nightMode by remember {
        mutableStateOf((context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
    }
    LaunchedEffect(permissionRefreshKey, manualRefreshKey) {
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
            val (cachedStats, lastScanAt) = readCachedHomeStats(context)
            val now = System.currentTimeMillis()
            val manualRefreshRequested = manualRefreshKey != lastManualRefreshHandled
            val cacheUsable = lastScanAt > 0L && now >= lastScanAt &&
                now - lastScanAt < HOME_STATS_CACHE_TTL_MS
            if (cachedStats.totalBytes > 0L || cachedStats.fileCount > 0 || lastScanAt > 0L) {
                homeStats = cachedStats
            }
            if (manualRefreshRequested || !cacheUsable) {
                statsLoading = true
                val freshStats = withContext(Dispatchers.IO) { scanHomeStats(context) }
                homeStats = freshStats
                saveCachedHomeStats(context, freshStats, System.currentTimeMillis())
                lastManualRefreshHandled = manualRefreshKey
                statsLoading = false
            } else {
                statsLoading = false
            }
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
        HomeCategory("ویدیوها", "فیلم‌ها و کلیپ‌ها", Icons.Default.VideoLibrary, "videos"),
        HomeCategory("تصاویر", "عکس‌ها و تصاویر", Icons.Default.Image, "images"),
        HomeCategory("موسیقی", "فایل‌های صوتی", Icons.Default.MusicNote, "audio"),
        HomeCategory("برنامه‌ها", "فایل‌های نصب برنامه", Icons.Default.Android, "apk"),
        HomeCategory("اسناد", "PDF، متن و آفیس", Icons.Default.Description, "documents")
    )

    val toolItems = listOf(
        CategoryItem("dual-pane", "نمایش دوگانه", "مدیریت هم‌زمان دو مسیر", Icons.Default.GridView, onClick = onDualPane),
        CategoryItem("transfer", "انتقال بی‌سیم", "ارسال و دریافت در شبکه", Icons.Default.Wifi, onClick = onShare),
        CategoryItem("vault", "خزانه", "دسترسی به فایل‌های امن", Icons.Default.Lock, onClick = onVault)
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
                    Column {
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(52.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                androidx.compose.foundation.Image(
                                    painter = painterResource(id = R.drawable.ic_launcher_fg),
                                    contentDescription = "Solina Files",
                                    modifier = Modifier.size(41.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Solina Files", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (statsLoading) "در حال شمارش فایل‌ها و پوشه‌ها…"
                                else "${persianDigits(String.format(Locale.ROOT, "%,d", homeStats.fileCount).replace(',', '٬'))} فایل · ${persianDigits(String.format(Locale.ROOT, "%,d", homeStats.folderCount).replace(',', '٬'))} پوشه",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(44.dp).clickable(onClick = onSettings)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Settings, contentDescription = "تنظیمات برنامه",
                                    tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(21.dp))
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(44.dp).clickable { toggleHomeTheme() }) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (nightMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                                    contentDescription = if (nightMode) "حالت تاریک" else "حالت روشن",
                                    tint = if (nightMode) Color(0xFF9C6BFF) else Color(0xFFFFC107),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    }
                }
                item {
                    val storageInfo = viewModelStorageInfo ?: StorageInfo(
                        totalBytes = homeStats.totalBytes,
                        usedBytes = (homeStats.totalBytes - homeStats.freeBytes).coerceAtLeast(0L)
                    )
                    val percent = storageInfo.usagePercent
                    val hasCapacity = hasAccess && !statsLoading && !viewModelStorageLoading && storageInfo.totalBytes > 0L
                    val statusColor = when {
                        !hasCapacity -> MaterialTheme.colorScheme.outline
                        percent >= 90 -> MaterialTheme.colorScheme.error
                        percent >= 70 -> Color(0xFFFFA000)
                        else -> Color(0xFF2E7D32)
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth().clickable { if (hasAccess) onBrowse() else onRequestPermission() }
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Storage, contentDescription = null, tint = statusColor, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("وضعیت حافظه", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.size(36.dp).clickable {
                                        if (hasAccess) {
                                            onManualRefresh()
                                            manualRefreshKey++
                                        } else onRequestPermission()
                                    }) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Refresh, contentDescription = "تازه‌سازی آمار حافظه و دسته‌بندی‌ها",
                                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (hasCapacity) "${persianDigits(percent.toString())}٪ پر"
                                    else if (!hasAccess) "نیازمند دسترسی" else "در حال محاسبه",
                                    color = statusColor, fontSize = 13.sp, fontWeight = FontWeight.Bold
                                )
                            }
                            LinearProgressIndicator(
                                progress = { if (hasCapacity) (percent / 100f).coerceIn(0f, 1f) else 0f },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                color = statusColor,
                                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (hasCapacity) "استفاده‌شده: ${storageInfo.formattedUsedGb()}" else "فضای استفاده‌شده: —",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (hasCapacity) "خالی: ${storageInfo.formattedFreeGb()}" else "فضای خالی: —",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        categories.forEach { category ->
                            val filter = category.filter ?: "recent"
                            val count = homeStats.categories[filter]?.count ?: 0
                            val iconColor = when (filter) {
                                "videos" -> Color(0xFF9C27B0)
                                "images" -> Color(0xFFE91E63)
                                "audio" -> Color(0xFFFFB300)
                                "apk" -> Color(0xFF43A047)
                                else -> Color(0xFF00897B)
                            }
                            Column(
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                    .clickable { onOpenCategory(category.title, filter) }
                                    .padding(vertical = 8.dp, horizontal = 1.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(category.icon, contentDescription = category.title, tint = iconColor, modifier = Modifier.size(32.5.dp))
                                Text(category.title, fontSize = 10.7.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val categoryBytes = homeStats.categories[filter]?.bytes ?: 0L
                                val categoryPercent = if (homeStats.totalBytes > 0L)
                                    ((categoryBytes.toDouble() / homeStats.totalBytes.toDouble()) * 100.0).toInt().coerceIn(0, 100)
                                else 0
                                Text(
                                    if (statsLoading) "در حال به‌روزرسانی…" else
                                        "${persianDigits(count.toString())} فایل · ${persianDigits(categoryPercent.toString())}٪ حافظه",
                                    fontSize = 10.7.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }
                item {
                    Surface(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth().height(1.dp)) {}
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onCleanTools).padding(horizontal = 8.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CleaningServices, contentDescription = null, tint = Color(0xFF8E63E6), modifier = Modifier.size(23.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("پاک‌سازی", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight, contentDescription = "ورود به پاک‌سازی", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    OutlinedTextField(
                        value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true, placeholder = { Text("جستجو در فایل‌ها و پوشه‌ها") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "جستجو") },
                        trailingIcon = { TextButton(onClick = { onSearch(search.trim()) }) { Text("بگرد") } },
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
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = false,
                            onClick = { onOpenCategory("فایل‌های بزرگ", "large") },
                            label = { Text("فایل‌های بزرگ") },
                            leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp)) })
                    }
                }
                item { Text("ابزارها", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        toolItems.forEach { tool ->
                            Surface(
                                modifier = Modifier.weight(1f).clickable(onClick = tool.onClick),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = tool.icon,
                                        contentDescription = tool.title,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Text(
                                        text = tool.title,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernStorageCard(
    info: StorageInfo,
    onCardClick: () -> Unit,
    modifier: Modifier = Modifier,
    onCleanTools: () -> Unit,
    isLoading: Boolean = false,
    hasAccess: Boolean = true,
    fileCount: Int? = null,
    folderCount: Int? = null
) {
    val percent = info.usagePercent
    val statusColor = when {
        percent > 75 -> MaterialTheme.colorScheme.error
        percent >= 50 -> Color(0xFFFFA000)
        else -> Color(0xFF2E7D32)
    }
    val hasCapacity = hasAccess && !isLoading && info.totalBytes > 0L

    androidx.compose.material3.ElevatedCard(
        onClick = onCardClick,
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "حافظه داخلی",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!hasAccess || isLoading || info.totalBytes <= 0L) {
                        Text(
                            text = when {
                                !hasAccess -> "برای مشاهده آمار، دسترسی لازم است"
                                isLoading -> "در حال محاسبه اطلاعات حافظه…"
                                else -> "اطلاعات حافظه در دسترس نیست"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (hasCapacity) "${persianDigits(percent.toString())}٪" else "—",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = statusColor
                )
            }

            if (hasCapacity) {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth().height(10.dp),
                    color = statusColor,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("استفاده‌شده", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(info.formattedUsedGb(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text("فضای آزاد", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(info.formattedFreeGb(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                if (fileCount != null || folderCount != null) {
                    Text(
                        text = listOfNotNull(
                            folderCount?.let { "${persianDigits(it.toString())} پوشه" },
                            fileCount?.let { "${persianDigits(it.toString())} فایل" }
                        ).joinToString("  •  "),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            TextButton(onClick = onCleanTools, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("پاک‌سازی سریع")
            }
        }
    }
}


@Composable
private fun CompositionLocalProviderCompatRtl(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        content = content
    )
}
