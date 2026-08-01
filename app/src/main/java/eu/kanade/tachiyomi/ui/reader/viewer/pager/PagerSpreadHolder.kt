package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import eu.kanade.tachiyomi.ui.reader.model.PageSpread
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.widget.ViewPagerAdapter

/**
 * T4 · one pager item holding two pages side by side.
 *
 * Each half is a real [PagerPageHolder], so loading, retry, error and zoom all keep working
 * exactly as they do for a single page — the spread only decides the arrangement, and puts
 * the later page on the left when the viewer reads right to left.
 */
@SuppressLint("ViewConstructor")
class PagerSpreadHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val spread: PageSpread,
) : LinearLayout(readerThemedContext), ViewPagerAdapter.PositionableView {

    override val item: Any get() = spread

    private val leading = PagerPageHolder(readerThemedContext, viewer, spread.first)
    private val trailing = PagerPageHolder(readerThemedContext, viewer, spread.second)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER

        val rightToLeft = viewer is R2LPagerViewer
        val plates = if (rightToLeft) listOf(trailing, leading) else listOf(leading, trailing)
        plates.forEach { plate ->
            addView(plate, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
    }

    /** The half showing [page], for pan and page-selected callbacks. */
    fun holderFor(page: ReaderPage): PagerPageHolder? = when (page) {
        spread.first -> leading
        spread.second -> trailing
        else -> null
    }

    fun onPageSelected(forward: Boolean) {
        leading.onPageSelected(forward)
        trailing.onPageSelected(forward)
    }

    fun canPanLeft(): Boolean = leading.canPanLeft()

    fun canPanRight(): Boolean = trailing.canPanRight()

    fun panLeft() = leading.panLeft()

    fun panRight() = trailing.panRight()
}
