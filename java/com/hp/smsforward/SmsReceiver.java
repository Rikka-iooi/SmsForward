package com.hp.smsforward;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;

/**
 * Manifest-declared SMS_RECEIVED receiver. It does not replace the system SMS app and does not
 * read the SMS provider. A received message is written to the local queue, then a JobScheduler
 * job performs the network delivery (with retries) outside the broadcast timeout.
 */
public class SmsReceiver extends android.content.BroadcastReceiver {
    private static final String TAG = "SmsForwarder";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
            return;
        }
        if (context.checkSelfPermission(Manifest.permission.RECEIVE_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length == 0) return;

        String sender = null;
        StringBuilder body = new StringBuilder();
        for (SmsMessage message : messages) {
            if (message == null) continue;
            if (sender == null) {
                sender = message.getDisplayOriginatingAddress();
                if (sender == null) sender = message.getOriginatingAddress();
            }
            String part = message.getMessageBody();
            if (part != null) body.append(part);
        }
        if (sender == null || sender.trim().isEmpty()) sender = "未知号码";
        if (body.length() == 0) return;

        long id = handleIncoming(context, sender, body.toString(), System.currentTimeMillis());
        if (id > 0) Log.i(TAG, "SMS queued, id=" + id);
    }

    /**
     * Shared queue path used by the real SMS broadcast and the debug build's fake-SMS receiver.
     * Returns the row id, or -1 when disabled/deduplicated.
     */
    public static long handleIncoming(Context context, String sender, String body, long now) {
        Context app = context.getApplicationContext();
        Config config = Config.load(app);
        if (!config.enabled) return -1;

        Db db = new Db(app);
        try {
            if (db.isDuplicate(sender, body, now)) return -1;
            long id = db.insert(sender, body, now);
            ForwardJobService.schedule(app);
            return id;
        } catch (Exception e) {
            Log.e(TAG, "Could not queue SMS", e);
            return -1;
        } finally {
            db.close();
        }
    }
}
