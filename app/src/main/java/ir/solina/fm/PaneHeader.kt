package ir.solina.fm

import android.content.Context
import android.graphics.Color
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
    private val onMenuClick: () -> Unit = {}
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

        addView(View(context).apply {
            setBackgroundColor(resolveThemeColor(MaterialR.attr.colorOutlineVariant, 0x33808080))
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
