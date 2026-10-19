package ir.solina.fm

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

/**
 * Loads storage capacity without blocking the UI thread.
 *
 * The app's storage permission flow is still managed by the activity. Call
 * refreshStorageStats() after access is granted and when the home screen resumes.
 */
class HomeViewModel : ViewModel() {

    private val _storageInfo = MutableStateFlow<StorageInfo?>(null)
    val storageInfo: StateFlow<StorageInfo?> = _storageInfo.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        refreshStorageStats()
    }

    fun refreshStorageStats() {
        viewModelScope.launch {
            _isLoading.value = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val path = Environment.getExternalStorageDirectory().path
                    val stat = StatFs(path)
                    val totalBytes = stat.totalBytes.coerceAtLeast(0L)
                    val freeBytes = stat.availableBytes.coerceIn(0L, totalBytes)
                    StorageInfo(
                        totalBytes = totalBytes,
                        usedBytes = (totalBytes - freeBytes).coerceAtLeast(0L)
                    )
                }.getOrNull()
            }
            _storageInfo.value = result
            _isLoading.value = false
        }
    }
}
