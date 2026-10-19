package ir.solina.fm

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * Reusable Android Views container for one Dual-Pane side.
 *
 * This project currently renders its file browser with RecyclerView/XML-style
 * Views, so this is the native Views equivalent of the proposed Compose
 * SinglePaneView. It owns the focus alpha and reports taps on empty panel space.
 */
class SinglePaneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var active: Boolean = false
    private var onActivate: (() -> Unit)? = null

    private var installedHeader: PaneHeader? = null

    /**
     * Installs the shared PaneHeader at the top of this pane, replacing any prior header.
     * The file list remains owned by the caller and stays below the header.
     */
    fun setPaneHeader(header: PaneHeader) {
        installedHeader?.let { old ->
            if (old.parent === this) removeView(old)
        }
        installedHeader = header
        if (header.parent !== this) {
            (header.parent as? android.view.ViewGroup)?.removeView(header)
            addView(header, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    /**
     * Optional ViewModel binding for callers that render directly from PaneState.
     * Call again when the pane state changes to update the displayed breadcrumb path.
     */
    fun bindPaneHeader(
        paneState: PaneState,
        viewModel: DualPaneViewModel,
        onMenuClick: () -> Unit
    ) {
        val header = PaneHeader(
            context = context,
            onNavigateUp = { viewModel.goUp(paneState.id) },
            onCrumbClick = { path -> viewModel.navigateToPath(paneState.id, path) },
            onToggleViewMode = { viewModel.toggleViewMode(paneState.id) },
            onMenuClick = onMenuClick
        )
        header.displayButton.setIconResource(
            if (paneState.isGridView) R.drawable.ic_list_view else R.drawable.ic_grid_view
        )
        header.displayButton.contentDescription =
            if (paneState.isGridView) "تغییر به نمایش فهرستی" else "تغییر به نمایش شبکه‌ای"
        header.setPath(
            path = if (paneState.isEmpty) "" else paneState.currentPath,
            emptyLabel = "انتخاب پوشه"
        )
        setPaneHeader(header)
    }

    init {
        orientation = VERTICAL
        isClickable = true
        isFocusable = true
        setOnClickListener { onActivate?.invoke() }
        applyFocusAppearance()
    }

    fun setOnActivateListener(listener: (() -> Unit)?) {
        onActivate = listener
    }

    fun setActive(isActive: Boolean) {
        if (active == isActive) return
        active = isActive
        applyFocusAppearance()
    }

    private fun applyFocusAppearance() {
        alpha = if (active) 1f else 0.6f
        isSelected = active
        contentDescription = if (active) "پنل فعال" else "پنل غیرفعال"
    }
}
