package com.hp.smsforward;

import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Formats the message and dispatches it to the active channel.
 */
final class Forwarder {
    static final int MAX_ATTEMPTS = 5;

    private Forwarder() {
    }

    static void send(Config config, SmsRecord record) throws Exception {
        if (Config.CHANNEL_WEBHOOK.equals(config.channel)) {
            WebhookForwarder.send(config, record);
        } else {
            EmailForwarder.send(config, subjectFor(record), bodyFor(record));
        }
    }

    static void sendTest(Config config, String testText) throws Exception {
        if (Config.CHANNEL_WEBHOOK.equals(config.channel)) {
            WebhookForwarder.sendTest(config, testText);
        } else {
            long now = System.currentTimeMillis();
            SmsRecord record = new SmsRecord(0, now, "短信转发",
                    testText, SmsRecord.STATUS_PENDING, 0, null, config.channel, null);
            EmailForwarder.send(config, "[短信转发] 测试邮件", bodyFor(record));
        }
    }

    static String subjectFor(SmsRecord record) {
        String sender = record.sender == null ? "未知号码" : record.sender;
        sender = sender.replace("\r", " ").replace("\n", " ").trim();
        if (sender.length() > 28) sender = sender.substring(0, 28) + "…";
        String time = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                .format(new Date(record.receivedAt));
        return "[短信] " + sender + " " + time;
    }

    static String bodyFor(SmsRecord record) {
        StringBuilder body = new StringBuilder();
        body.append("【短信转发】\n\n");
        body.append("发件人：").append(record.sender == null || record.sender.isEmpty()
                ? "未知号码" : record.sender).append('\n');
        body.append("接收时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date(record.receivedAt))).append('\n');
        body.append("设备：").append(deviceName()).append("（Android ")
                .append(Build.VERSION.RELEASE).append("）\n");
        body.append("\n──────────\n\n");
        body.append(record.body == null ? "" : record.body).append('\n');
        body.append("\n──────────\n");
        body.append("本邮件由手机上的“短信转发”自动发送。\n");
        return body.toString();
    }

    static String testText() {
        return "这是一条测试消息，说明当前的转发配置可以正常使用。\n\n"
                + "设备：" + deviceName() + "\n"
                + "时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date()) + "\n\n"
                + "如果你收到了这条消息，就可以回到 App 开启自动转发了。";
    }

    static String deviceName() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (model.toLowerCase(Locale.US).startsWith(manufacturer.toLowerCase(Locale.US))) {
            return model;
        }
        return (manufacturer + " " + model).trim();
    }
}
