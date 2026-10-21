package ir.solina.fm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import ir.solina.fm.dualpane.ExplorerScreen
import ir.solina.fm.dualpane.ExplorerViewModel
import ir.solina.fm.dualpane.DualPaneMode

/** Entry point for the new Strata-style dual-pane explorer. */
class StrataDualPaneActivity : ComponentActivity() {
    private lateinit var explorerViewModel: ExplorerViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        explorerViewModel = ViewModelProvider(this)[ExplorerViewModel::class.java]
        val preferences = getSharedPreferences("solina_dual_pane", MODE_PRIVATE)
        explorerViewModel.setBookmarkedPaths(
            preferences.getStringSet("bookmarks", emptySet())?.toSet().orEmpty()
        )
        val savedMode = runCatching {
            DualPaneMode.valueOf(preferences.getString("display_mode", DualPaneMode.LANDSCAPE_ONLY.name)!!)
        }.getOrDefault(DualPaneMode.LANDSCAPE_ONLY)
        explorerViewModel.setDualPaneMode(savedMode)
        setContent {
            MaterialTheme {
                Surface {
                    ExplorerScreen(
                        viewModel = explorerViewModel,
                        onDualPaneModeChanged = { mode ->
                            preferences.edit().putString("display_mode", mode.name).apply()
                        },
                        onBookmarksChanged = { paths ->
                            preferences.edit().putStringSet("bookmarks", paths.toSet()).apply()
                        }
                    )
                }
            }
        }
    }
}
