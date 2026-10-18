package ir.solina.fm

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class HomeCategoryActivity : ComponentActivity() {
    private var resumeVersion by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeVersion++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra("category_title") ?: "فایل‌ها"
        val category = intent.getStringExtra("category_filter") ?: "recent"
        setContent {
            MaterialTheme {
                CategoryFilesScreen(
                    title = title,
                    category = category,
                    permissionRefreshKey = resumeVersion,
                    onBack = { finish() },
                    onRequestPermission = {
                        startActivity(Intent(this, MainActivity::class.java)
                            .putExtra("request_storage_permission", true))
                    },
                    onOpenFile = { path ->
                        startActivity(Intent(this, MainActivity::class.java)
                            .putExtra("open_recent_path", path))
                    }
                )
            }
        }
    }
}

private val categoryImageExtensions = setOf(
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "tif", "tiff", "svg", "avif", "raw", "dng"
)
private val categoryVideoExtensions = setOf(
    "mp4", "mkv", "mov", "webm", "3gp", "avi", "mpeg", "mpg", "m4v", "wmv", "flv", "ts", "mts"
)
private val categoryAudioExtensions = setOf(
    "mp3", "m4a", "aac", "wav", "ogg", "oga", "flac", "opus", "mid", "midi", "wma", "amr", "aiff"
)
private val categoryArchiveExtensions = setOf(
    "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "zst", "tgz", "tbz2", "iso", "cab", "jar", "war"
)
private val categoryDocumentExtensions = setOf(
    "pdf", "doc", "docx", "docm", "odt", "rtf", "txt", "text", "md", "markdown", "log",
    "xls", "xlsx", "xlsm", "ods", "csv", "ppt", "pptx", "pptm", "odp",
    "html", "htm", "xml", "json", "yaml", "yml", "epub", "pages", "numbers", "key"
)

private fun categoryMatches(file: File, category: String): Boolean {
    if (!file.isFile) return false
    val ext = file.extension.lowercase(Locale.ROOT)
    return when (category.lowercase(Locale.ROOT)) {
        "images" -> ext in categoryImageExtensions
        "videos" -> ext in categoryVideoExtensions
        "audio" -> ext in categoryAudioExtensions
        "documents" -> ext in categoryDocumentExtensions || ext in categoryArchiveExtensions
        "apk" -> ext == "apk"
        "recent", "downloads", "" -> true
        else -> true
    }
}

/**
 * Uses modification time as the available recency signal. Android's ordinary File API
 * does not provide a reliable creation/download timestamp for every file system.
 */
private fun scanCategoryFiles(context: android.content.Context, category: String): List<File> {
    val roots = try {
        val storageManager = context.getSystemService(StorageManager::class.java)
        storageManager.storageVolumes.mapNotNull { volume ->
            val state = volume.state
            if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) {
                null
            } else {
                volume.directory?.takeIf { it.exists() && it.isDirectory }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }.ifEmpty {
        listOf(Environment.getExternalStorageDirectory())
    }

    val found = ArrayList<File>()
    val stack = ArrayDeque<File>()
    val visitedDirectories = HashSet<String>()
    roots.forEach { root ->
        if (root.exists() && root.isDirectory) stack.addLast(root)
    }

    // Walk every accessible mounted volume without an arbitrary file-count/depth cap.
    // Canonical-path tracking avoids revisiting the same directory through aliases.
    while (stack.isNotEmpty()) {
        val directory = stack.removeLast()
        val canonicalDirectory = try {
            directory.canonicalPath
        } catch (_: Exception) {
            directory.absolutePath
        }
        if (!visitedDirectories.add(canonicalDirectory)) continue

        val children = try {
            directory.listFiles()
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        } ?: continue

        for (child in children) {
            if (child.name.startsWith(".solina-trash-")) continue
            try {
                if (child.isFile && categoryMatches(child, category)) {
                    found.add(child)
                } else if (child.isDirectory) {
                    stack.addLast(child)
                }
            } catch (_: SecurityException) {
                // A restricted directory must not prevent scanning other accessible folders.
            }
        }
    }

    return found.distinctBy { it.absolutePath }.sortedWith(
        compareByDescending<File> { it.lastModified() }
            .thenBy { it.name.lowercase(Locale.ROOT) }
    )
}

@Composable
private fun CategoryFilesScreen(
    title: String,
    category: String,
    permissionRefreshKey: Int,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenFile: (String) -> Unit
) {
    var files by remember(category) { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember(category) { mutableStateOf(true) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val hasAccess = remember(refreshKey, permissionRefreshKey) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    LaunchedEffect(category, refreshKey, hasAccess, permissionRefreshKey) {
        if (hasAccess) {
            loading = true
            files = withContext(Dispatchers.IO) { scanCategoryFiles(context, category) }
            loading = false
        } else {
            files = emptyList()
            loading = false
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text("بازگشت") }
                Text(
                    title,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = { refreshKey++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = "تازه‌سازی", modifier = Modifier.size(18.dp))
                }
            }
            Text(
                if (loading) "در حال بررسی فایل‌های حافظه…" else "مرتب‌شده از جدیدترین به قدیمی‌ترین • ${files.size} فایل",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            when {
                !hasAccess -> Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("برای جست‌وجوی فایل‌ها در سراسر حافظه، دسترسی همهٔ فایل‌ها لازم است.")
                    Button(onClick = onRequestPermission) { Text("اعطای دسترسی") }
                }
                loading -> Text("لطفاً صبر کنید…", modifier = Modifier.padding(20.dp))
                files.isEmpty() -> Text(
                    "فایلی در این دسته پیدا نشد.",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(files, key = { it.absolutePath }) { file ->
                        CategoryFileRow(file = file, onClick = { onOpenFile(file.absolutePath) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryFileRow(file: File, onClick: () -> Unit) {
    val extension = file.extension.lowercase(Locale.ROOT)
    val icon: ImageVector = when {
        extension in categoryImageExtensions -> Icons.Default.Image
        extension in categoryVideoExtensions -> Icons.Default.VideoLibrary
        extension in categoryAudioExtensions -> Icons.Default.MusicNote
        extension == "apk" -> Icons.Default.Android
        extension in categoryArchiveExtensions -> Icons.Default.Archive
        extension in categoryDocumentExtensions -> Icons.Default.Description
        else -> Icons.Default.InsertDriveFile
    }
    val modified = remember(file.absolutePath, file.lastModified()) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(file.lastModified()))
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(10.dp).size(22.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                file.parent ?: file.absolutePath,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${android.text.format.Formatter.formatFileSize(context, file.length())} • $modified",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    Divider(color = MaterialTheme.colorScheme.surfaceVariant)
}
