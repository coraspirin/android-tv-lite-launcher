package local.kutu.home;

import android.graphics.drawable.Drawable;

/**
 * One launchable app, or a remembered favourite whose app is currently missing.
 * Guide 19: a missing favourite keeps its remembered label and must not crash anything.
 */
final class AppEntry {

    final String pkg;
    final String label;
    final Drawable icon;
    final boolean available;
    final boolean uninstallable;

    AppEntry(String pkg, String label, Drawable icon, boolean available, boolean uninstallable) {
        this.pkg = pkg;
        this.label = label;
        this.icon = icon;
        this.available = available;
        this.uninstallable = uninstallable;
    }

    /** Placeholder for a favourite that is no longer installed. */
    static AppEntry missing(String pkg, String rememberedLabel) {
        String shown = (rememberedLabel == null || rememberedLabel.isEmpty()) ? pkg : rememberedLabel;
        return new AppEntry(pkg, shown, null, false, false);
    }
}
