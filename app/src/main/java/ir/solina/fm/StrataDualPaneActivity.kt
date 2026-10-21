package ir.solina.fm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import ir.solina.fm.dualpane.ExplorerScreen
import ir.solina.fm.dualpane.ExplorerViewModel

/** Entry point for the new Strata-style dual-pane explorer. */
class StrataDualPaneActivity : ComponentActivity() {
    private lateinit var explorerViewModel: ExplorerViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        explorerViewModel = ViewModelProvider(this)[ExplorerViewModel::class.java]
        setContent {
            MaterialTheme {
                Surface {
                    ExplorerScreen(viewModel = explorerViewModel)
                }
            }
        }
    }
}
