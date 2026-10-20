package ir.solina.fm

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton

/**
 * Native Android Views equivalent of the proposed Compose PaneHeader.
 * Keeps navigation controls on the first row and breadcrumbs on the second.
 */
class PaneHeader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    private val onNavigateUp: () -> Unit = {},
    private val onCrumbClick: (String) -> Unit = {},
    private val onToggleViewMode: () -> Unit = {},
    private val onMenuClick: () -> Unit = {},
    private val onSelectAll: () -> Unit = {},
    private val onCopyToOther: () -> Unit = {},
    private val onMoveToOther: () -> Unit = {}
) : LinearLayout(context, attrs, defStyleAttr) {

    val breadcrumbBar = BreadcrumbBar(context)
    val displayButton: MaterialButton
    val menuButton: MaterialButton

    private val upButton: MaterialButton

    init {
        orientation = VERTICAL
        val controls = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), 0)
        }

        upButton = MaterialButton(context, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
            text = "↑"
            textSize = 18f
            minWidth = dp(40)
            minimumWidth = dp(40)
            minHeight = dp(40)
            setPadding(0, 0, 0, 0)
            contentDescription = "رفتن به پوشه والد"
            setOnClickListener { onNavigateUp() }
        }
        val flexibleSpace = View(context)
        displayButton = MaterialButton(context, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
            setIconResource(R.drawable.ic_grid_view)
            minWidth = dp(40)
            minimumWidth = dp(40)
            minHeight = dp(40)
            setPadding(0, 0, 0, 0)
            contentDescription = "تغییر حالت نمایش"
            setOnClickListener { onToggleViewMode() }
        }
        menuButton = MaterialButton(context, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
            setIconResource(R.drawable.ic_more_vert)
            minWidth = dp(40)
            minimumWidth = dp(40)
            minHeight = dp(40)
            setPadding(0, 0, 0, 0)
            contentDescription = "گزینه‌های پنل"
            setOnClickListener { onMenuClick() }
        }

        controls.addView(upButton, LayoutParams(dp(40), dp(42)))
        controls.addView(flexibleSpace, LayoutParams(0, dp(1), 1f))
        controls.addView(displayButton, LayoutParams(dp(40), dp(42)))
        controls.addView(menuButton, LayoutParams(dp(40), dp(42)))
        addView(controls, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))

        addView(breadcrumbBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(42)))

        // Pane-local quick actions stay attached to this pane even when the other pane has focus.
        val quickActionsScroll = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val quickActions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        fun addQuickAction(icon: Int, description: String, action: () -> Unit) {
            val button = MaterialButton(context, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
                text = ""
                setIconResource(icon)
                iconSize = dp(19)
                minWidth = dp(38)
                minimumWidth = dp(38)
                minHeight = dp(34)
                insetTop = 0
                insetBottom = 0
                setPadding(0, 0, 0, 0)
                contentDescription = description
                tooltipText = description
                setOnClickListener { action() }
            }
            quickActions.addView(button, LayoutParams(dp(40), dp(36)).apply {
                marginEnd = dp(4)
            })
        }
        addQuickAction(R.drawable.ic_select_all, "انتخاب همه فایل‌های این پنل", onSelectAll)
        addQuickAction(R.drawable.ic_content_copy, "کپی انتخاب‌ها به پنل مقابل", onCopyToOther)
        addQuickAction(R.drawable.ic_content_cut, "انتقال انتخاب‌ها به پنل مقابل", onMoveToOther)
        quickActionsScroll.addView(quickActions, LayoutParams(LayoutParams.WRAP_CONTENT, dp(38)))
        addView(quickActionsScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(38)))

        addView(View(context).apply {
            setBackgroundColor(resolveThemeColor(MaterialR.attr.colorOnSurfaceVariant, 0x33808080))
            alpha = 0.5f
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))
    }

    fun setPath(path: String, emptyLabel: String = "انتخاب پوشه") {
        breadcrumbBar.setPath(path, onCrumbClick, emptyLabel)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun resolveThemeColor(attribute: Int, fallback: Int): Int {
        val value = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attribute, value, true)) {
            if (value.resourceId != 0) {
                androidx.core.content.ContextCompat.getColor(context, value.resourceId)
            } else value.data
        } else fallback
    }
}
