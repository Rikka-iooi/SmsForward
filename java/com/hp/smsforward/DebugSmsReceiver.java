package com.hp.smsforward;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.util.Base64;
import android.util.Log;

/**
 * TEST-ONLY receiver. It is compiled only into the debug APK and is intentionally absent from
 * the release manifest. It lets the build be exercised over adb without waiting for a real SMS.
 */
public class DebugSmsReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsForwardDebug";
    public static final String ACTION_SMS = "com.hp.smsforward.DEBUG_SMS";
    public static final String ACTION_WEBHOOK = "com.hp.smsforward.DEBUG_WEBHOOK";
    public static final String ACTION_EMAIL = "com.hp.smsforward.DEBUG_EMAIL";
    public static final String ACTION_TEST = "com.hp.smsforward.DEBUG_TEST";
    public static final String ACTION_DISABLE = "com.hp.smsforward.DEBUG_DISABLE";
    public static final String ACTION_PDU = "com.hp.smsforward.DEBUG_PDU";
    public static final String ACTION_SEND_SMS = "com.hp.smsforward.DEBUG_SEND_SMS";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        try {
            if (ACTION_SMS.equals(action)) {
                String sender = intent.getStringExtra("sender");
                String body = intent.getStringExtra("body");
                if (body == null) body = "";
                long id = SmsReceiver.handleIncoming(context,
                        sender == null ? "10086" : sender, body, System.currentTimeMillis());
                Log.i(TAG, "fake_sms_queued id=" + id);
            } else if (ACTION_WEBHOOK.equals(action)) {
                Config config = Config.load(context);
                config.channel = Config.CHANNEL_WEBHOOK;
                config.webhookUrl = intent.getStringExtra("url");
                config.enabled = true;
                config.save(context);
                ForwardJobService.schedule(context);
                Log.i(TAG, "webhook_configured url=" + config.webhookUrl);
            } else if (ACTION_EMAIL.equals(action)) {
                Config config = Config.load(context);
                config.channel = Config.CHANNEL_EMAIL;
                config.provider = Config.PROVIDER_CUSTOM;
                config.smtpHost = intent.getStringExtra("host");
                config.smtpPort = intent.getIntExtra("port", 465);
                config.smtpSecurity = intent.getStringExtra("security");
                config.smtpUser = intent.getStringExtra("user");
                config.smtpPassword = intent.getStringExtra("pass");
                config.recipients = intent.getStringExtra("recipients");
                config.enabled = intent.getBooleanExtra("enabled", false);
                config.save(context);
                Log.i(TAG, "email_configured host=" + config.smtpHost + " port=" + config.smtpPort);
            } else if (ACTION_TEST.equals(action)) {
                final PendingResult pending = goAsync();
                final Context app = context.getApplicationContext();
                new Thread(() -> {
                    try {
                        Forwarder.sendTest(Config.load(app), Forwarder.testText());
                        Log.i(TAG, "TEST_OK");
                    } catch (Throwable t) {
                        Log.e(TAG, "TEST_FAIL " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    } finally {
                        pending.finish();
                    }
                }, "debug-forward-test").start();
            } else if (ACTION_DISABLE.equals(action)) {
                Config config = Config.load(context);
                config.enabled = false;
                config.save(context);
                ForwardJobService.cancel(context);
                Log.i(TAG, "disabled");
            } else if (ACTION_PDU.equals(action)) {
                String hex = intent.getStringExtra("pdu");
                byte[] pdu = Base64.decode(hex, Base64.DEFAULT);
                Intent smsIntent = new Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION);
                smsIntent.putExtra("pdus", new Object[]{pdu});
                smsIntent.putExtra("format", "3gpp");
                new SmsReceiver().onReceive(context, smsIntent);
                Log.i(TAG, "pdu_delivered bytes=" + pdu.length);
            } else if (ACTION_SEND_SMS.equals(action)) {
                String dest = intent.getStringExtra("dest");
                String body = intent.getStringExtra("body");
                if (dest == null) dest = "10086";
                if (body == null) body = "CXLL";
                SmsManager.getDefault().sendTextMessage(dest, null, body, null, null);
                Log.i(TAG, "sms_send_requested dest=" + dest);
            }
        } catch (Throwable t) {
            Log.e(TAG, "debug_action_failed " + action, t);
        }
    }
}
