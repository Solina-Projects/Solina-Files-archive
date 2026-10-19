package ir.solina.fm

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import java.io.File

/**
 * Native Android Views breadcrumb bar for the existing XML/RecyclerView UI.
 * Each path segment is a compact clickable chip; the current segment is highlighted.
 */
class BreadcrumbBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    private val chips = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(chips, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        contentDescription = "نوار مسیریابی پوشه"
    }

    /**
     * @param path absolute path for the current directory; blank means an unselected pane.
     * @param onCrumbClick receives the selected crumb's full path (blank for Home).
     * @param emptyLabel label shown when no folder has been chosen for this pane.
     */
    fun setPath(
        path: String,
        onCrumbClick: (String) -> Unit,
        emptyLabel: String = "انتخاب پوشه"
    ) {
        chips.removeAllViews()
        if (path.isBlank()) {
            chips.addView(createChip(emptyLabel, selected = true) { onCrumbClick("") },
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)))
            return
        }

        val crumbs = PathUtils.parsePathToCrumbs(path)
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                chips.addView(TextView(context).apply {
                    text = "›"
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setTextColor(resolveThemeColor(MaterialR.attr.colorOnSurfaceVariant))
                    contentDescription = null
                }, LinearLayout.LayoutParams(dp(18), dp(40)))
            }
            chips.addView(
                createChip(crumb.name, selected = index == crumbs.lastIndex) {
                    onCrumbClick(crumb.fullPath)
                },
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(40))
            )
        }
        post { fullScroll(View.FOCUS_RIGHT) }
    }

    private fun createChip(label: String, selected: Boolean, onClick: () -> Unit): MaterialButton =
        MaterialButton(
            context,
            null,
            if (selected) MaterialR.attr.materialButtonOutlinedStyle
            else MaterialR.attr.materialButtonTextButtonStyle
        ).apply {
            text = label
            isAllCaps = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            minHeight = dp(36)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(6), 0, dp(6), 0)
            contentDescription = "رفتن به $label"
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun resolveThemeColor(attribute: Int): Int {
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(attribute, typedValue, true)
        return if (typedValue.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(context, typedValue.resourceId)
        } else typedValue.data
    }
}
