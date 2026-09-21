package local.kutu.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads launchable apps from the device. Guide 16: prefer Leanback launch intents,
 * with a safe normal-launcher fallback. Guide 23: exclude Kutu Home, Kutu Mirror and
 * components that are not user-facing.
 */
final class AppRepository {

    /** Kept in one place so the launcher never lists the receiver it controls. */
    static final String MIRROR_PKG = "local.kutu.mirror";
    /** The transfer app, like the mirror app, is reached from a chip - not the grid. */
    static final String TRANSFER_PKG = "local.kutu.transfer";

    private AppRepository() {
    }

    /** All user-facing launchable apps, de-duplicated by package, alphabetical. */
    static List<AppEntry> loadLaunchable(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, AppEntry> byPkg = new LinkedHashMap<>();

        // Leanback first: on a TV these carry the better activity icon.
        collect(ctx, pm, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), byPkg);
        collect(ctx, pm, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), byPkg);

        List<AppEntry> out = new ArrayList<>(byPkg.values());
        final Collator collator = Collator.getInstance(new Locale("tr", "TR"));
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(out, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return collator.compare(a.label, b.label);
            }
        });
        return out;
    }

    private static void collect(Context ctx, PackageManager pm, Intent probe, Map<String, AppEntry> sink) {
        List<ResolveInfo> hits;
        try {
            hits = pm.queryIntentActivities(probe, 0);
        } catch (Exception e) {
            return;
        }
        if (hits == null) return;

        String self = ctx.getPackageName();
        for (ResolveInfo ri : hits) {
            if (ri == null || ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null) continue;
            if (pkg.equals(self) || pkg.equals(MIRROR_PKG)
                    || pkg.equals(TRANSFER_PKG)) continue;              // guide 23
            if (sink.containsKey(pkg)) continue;

            CharSequence lbl = ri.loadLabel(pm);
            String label = lbl == null ? pkg : lbl.toString().trim();
            if (label.isEmpty()) label = pkg;

            Drawable icon = null;
            try {
                icon = ri.activityInfo.loadIcon(pm);   // activity icon, falls back to app icon
            } catch (Exception ignored) {
            }
            if (icon == null) {
                try {
                    icon = pm.getApplicationIcon(pkg);
                } catch (Exception ignored) {
                }
            }

            sink.put(pkg, new AppEntry(pkg, label, icon, true, isUninstallable(pm, pkg)));
        }
    }

    /** Look one package up for the dock; returns a missing placeholder when absent. */
    static AppEntry lookup(Context ctx, String pkg, String rememberedLabel) {
        PackageManager pm = ctx.getPackageManager();
        Intent launch = launchIntent(ctx, pkg);
        if (launch == null) return AppEntry.missing(pkg, rememberedLabel);

        String label = rememberedLabel;
        Drawable icon = null;
        try {
            ResolveInfo ri = pm.resolveActivity(launch, 0);
            if (ri != null) {
                CharSequence lbl = ri.loadLabel(pm);
                if (lbl != null && lbl.length() > 0) label = lbl.toString().trim();
                icon = ri.activityInfo != null ? ri.activityInfo.loadIcon(pm) : null;
            }
            if (icon == null) icon = pm.getApplicationIcon(pkg);
            if (label == null || label.isEmpty()) {
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            }
        } catch (Exception ignored) {
        }
        if (label == null || label.isEmpty()) label = pkg;
        return new AppEntry(pkg, label, icon, true, isUninstallable(pm, pkg));
    }

    /** Leanback intent first, normal launcher intent as fallback. */
    static Intent launchIntent(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = null;
        try {
            i = pm.getLeanbackLaunchIntentForPackage(pkg);
        } catch (Exception ignored) {
        }
        if (i == null) {
            try {
                i = pm.getLaunchIntentForPackage(pkg);
            } catch (Exception ignored) {
            }
        }
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }

    static boolean isInstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Guide 21: offer uninstall only for a normal, uninstallable user app.
     * A system app, or an update to one, is never offered.
     */
    static boolean isUninstallable(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean updatedSystem = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            return !system && !updatedSystem;
        } catch (Exception e) {
            return false;
        }
    }

    /** Android's own App Info screen. */
    static Intent appInfoIntent(String pkg) {
        Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.fromParts("package", pkg, null));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /**
     * Android's own uninstall confirmation. Guide 21: never uninstall silently.
     */
    static Intent uninstallIntent(String pkg) {
        Intent i = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.fromParts("package", pkg, null));
        i.putExtra(Intent.EXTRA_RETURN_RESULT, false);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }
}
