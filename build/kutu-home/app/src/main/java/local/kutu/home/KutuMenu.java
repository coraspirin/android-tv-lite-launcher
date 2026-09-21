package local.kutu.home;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Guide 21: the context menu. Dark glass, D-pad focus trapped inside, BACK closes,
 * subtle veil behind. The window background is fully transparent and no elevation is
 * set on the card, so the rounded drawable never sits on a square shadow artifact.
 * Row text is centred and wraps.
 */
final class KutuMenu extends Dialog {

    private final String title;
    private final List<Item> items = new ArrayList<>();

    static final class Item {
        final String label;
        final Runnable action;

        Item(String label, Runnable action) {
            this.label = label;
            this.action = action;
        }
    }

    private KutuMenu(Context ctx, String title) {
        super(ctx, android.R.style.Theme_Material_NoActionBar);
        this.title = title;
    }

    static Builder on(Context ctx, String title) {
        return new Builder(ctx, title);
    }

    static final class Builder {
        private final KutuMenu menu;

        Builder(Context ctx, String title) {
            menu = new KutuMenu(ctx, title);
        }

        Builder add(String label, Runnable action) {
            menu.items.add(new Item(label, action));
            return this;
        }

        Builder addIf(boolean condition, String label, Runnable action) {
            if (condition) add(label, action);
            return this;
        }

        KutuMenu show() {
            menu.show();
            return menu;
        }
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.setDimAmount(0f);              // our own veil is in the layout
            w.setWindowAnimations(0);
        }
        setCanceledOnTouchOutside(true);

        View root = LayoutInflater.from(getContext()).inflate(R.layout.dialog_menu, null, false);
        setContentView(root);

        TextView titleView = root.findViewById(R.id.menu_title);
        if (title == null || title.isEmpty()) {
            titleView.setVisibility(View.GONE);
        } else {
            titleView.setText(title);
        }

        LinearLayout rows = root.findViewById(R.id.menu_rows);
        LayoutInflater inflater = LayoutInflater.from(getContext());
        View first = null;

        for (final Item item : items) {
            TextView row = (TextView) inflater.inflate(R.layout.view_menu_row, rows, false);
            row.setText(item.label);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (item.action != null) item.action.run();
                }
            });
            rows.addView(row);
            if (first == null) first = row;
        }

        if (first != null) {
            final View target = first;
            target.post(new Runnable() {
                @Override
                public void run() {
                    target.requestFocus();
                }
            });
        }
    }
}
