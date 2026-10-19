package ir.solina.fm

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/**
 * Native Android Views equivalent of the proposed Compose DualPaneScreen.
 * Hosts two independently navigable pane views and a divider, supporting
 * side-by-side and stacked layouts.
 */
class DualPaneScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class Arrangement { SIDE_BY_SIDE, STACKED }

    private var leftPane: View? = null
    private var rightPane: View? = null
    private var dividerColor: Int = 0x33808080
    private var dividerSizePx: Int = (context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    init {
        orientation = HORIZONTAL
    }

    fun setPanes(
        left: View,
        right: View,
        arrangement: Arrangement,
        dividerColor: Int,
        dividerSizePx: Int
    ) {
        leftPane = left
        rightPane = right
        this.dividerColor = dividerColor
        this.dividerSizePx = dividerSizePx.coerceAtLeast(1)
        orientation = if (arrangement == Arrangement.SIDE_BY_SIDE) HORIZONTAL else VERTICAL
        removeAllViews()

        val paneParams = if (arrangement == Arrangement.SIDE_BY_SIDE) {
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        } else {
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        addView(left, paneParams)
        addView(View(context).apply {
            background = GradientDrawable().apply { setColor(this@DualPaneScreen.dividerColor) }
            isFocusable = false
            isClickable = false
            contentDescription = "جداکننده پنل‌ها"
        }, if (arrangement == Arrangement.SIDE_BY_SIDE) {
            LayoutParams(this.dividerSizePx, LayoutParams.MATCH_PARENT)
        } else {
            LayoutParams(LayoutParams.MATCH_PARENT, this.dividerSizePx)
        })
        addView(right, paneParams)
    }
}
