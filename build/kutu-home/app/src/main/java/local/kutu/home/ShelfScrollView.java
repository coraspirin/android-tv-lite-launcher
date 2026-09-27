package local.kutu.home;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.HorizontalScrollView;

/**
 * The shelf's scroller, which only ever stops on whole tiles.
 *
 * The stock HorizontalScrollView scrolls just far enough to reveal the focused child and
 * adds its fading-edge length on top, so after a few moves the shelf rests between tiles:
 * a sliver of the previous card shows at one end and the focused tile sits half outside
 * the clip box at the other, cutting its focus ring off. Here the scroll target is always
 * a whole number of tiles, so the N tiles in view are complete, the row padding at either
 * end falls on a neighbour's transparent inset, and the focused tile always has its full
 * room inside the clip.
 */
public final class ShelfScrollView extends HorizontalScrollView {

    public ShelfScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        if (getChildCount() == 0) return 0;
        View row = getChildAt(0);
        int pitch = rect.width();
        if (pitch <= 0) return 0;
        int rowPad = row.getPaddingLeft();
        int viewport = getWidth() - getPaddingLeft() - getPaddingRight();
        int visible = Math.max(1, (viewport - rowPad - row.getPaddingRight()) / pitch);
        int maxScroll = Math.max(0, row.getWidth() - viewport);

        int index = Math.round((rect.left - row.getLeft() - rowPad) / (float) pitch);
        int first = Math.round(getScrollX() / (float) pitch);
        if (index < first) first = index;
        else if (index > first + visible - 1) first = index - visible + 1;

        int target = Math.max(0, Math.min(maxScroll, first * pitch));
        return target - getScrollX();
    }
}
