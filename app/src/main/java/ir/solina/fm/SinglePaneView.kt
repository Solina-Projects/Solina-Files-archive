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
