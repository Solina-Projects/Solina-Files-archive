package ir.solina.fm

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Storage capacity cache with a 24-hour automatic refresh interval. */
class HomeViewModel : ViewModel() {
    private val _storageInfo = MutableStateFlow<StorageInfo?>(null)
    val storageInfo: StateFlow<StorageInfo?> = _storageInfo.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private var appContext: Context? = null

    fun attachContext(context: Context) {
        appContext = context.applicationContext
        refreshStorageStats()
    }

    fun refreshStorageStats(force: Boolean = false) {
        viewModelScope.launch {
            val context = appContext ?: return@launch
            val preferences = context.getSharedPreferences("solina_home_stats_cache", Context.MODE_PRIVATE)
            val savedAt = preferences.getLong("capacity_last_scan_at", 0L)
            val now = System.currentTimeMillis()
            val cachedTotal = preferences.getLong("capacity_total_bytes", 0L)
            val cachedUsed = preferences.getLong("capacity_used_bytes", 0L)
            if (cachedTotal > 0L) _storageInfo.value = StorageInfo(cachedTotal, cachedUsed)
            if (!force && cachedTotal > 0L && now >= savedAt && now - savedAt < 24L * 60L * 60L * 1000L) {
                _isLoading.value = false
                return@launch
            }
            _isLoading.value = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val stat = StatFs(Environment.getExternalStorageDirectory().path)
                    val totalBytes = stat.totalBytes.coerceAtLeast(0L)
                    val freeBytes = stat.availableBytes.coerceIn(0L, totalBytes)
                    StorageInfo(totalBytes, (totalBytes - freeBytes).coerceAtLeast(0L))
                }.getOrNull()
            }
            if (result != null) {
                _storageInfo.value = result
                preferences.edit()
                    .putLong("capacity_last_scan_at", System.currentTimeMillis())
                    .putLong("capacity_total_bytes", result.totalBytes)
                    .putLong("capacity_used_bytes", result.usedBytes)
                    .apply()
            }
            _isLoading.value = false
        }
    }
}
