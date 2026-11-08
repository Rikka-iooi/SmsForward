package com.hp.smsforward;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Reschedules queued messages after a reboot or an app update.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        Config config = Config.load(context);
        if (!config.enabled) return;
        Db db = new Db(context);
        try {
            if (db.countPending() > 0) ForwardJobService.schedule(context);
        } finally {
            db.close();
        }
    }
}
