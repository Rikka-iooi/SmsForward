package com.hp.smsforward;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * Best-effort shortcuts to OEM background/autostart screens. No OEM API is public, so every
 * attempt falls back to the standard app-details page.
 */
final class DeviceSettings {
    private static final String[][] AUTO_START_COMPONENTS = {
            {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
            {"com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"},
            {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
            {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
            {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
            {"com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"},
            {"com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"},
            {"com.asus.mobilemanager", "com.asus.mobilemanager.MainActivity"}
    };

    private static final String[][] AUTO_START_ACTIONS = {
            {"miui.intent.action.OP_AUTO_START", "com.miui.securitycenter"},
            {"com.huawei.systemmanager.startupmgr.action.APP_STARTUP", "com.huawei.systemmanager"},
            {"com.coloros.safecenter.action.STARTUP_APP", "com.coloros.safecenter"}
    };

    private DeviceSettings() {
    }

    static boolean openAutoStart(Context context) {
        for (String[] component : AUTO_START_COMPONENTS) {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(component[0], component[1]));
            if (start(context, intent)) return true;
        }
        for (String[] action : AUTO_START_ACTIONS) {
            Intent intent = new Intent(action[0]);
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            intent.setPackage(action[1]);
            if (start(context, intent)) return true;
        }
        return false;
    }

    static void openAppDetails(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName()));
        start(context, intent);
    }

    /**
     * Tries the standard Doze whitelist dialog first. If it is unavailable (or the app is already
     * whitelisted), opens app details so the OEM's own battery screen can be reached.
     */
    static boolean openBatterySettings(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (powerManager != null
                && !powerManager.isIgnoringBatteryOptimizations(context.getPackageName())) {
            Intent request = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + context.getPackageName()));
            if (start(context, request)) return true;
        }
        openAppDetails(context);
        return true;
    }

    static boolean isIgnoringBatteryOptimizations(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager != null
                && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    private static boolean start(Context context, Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
