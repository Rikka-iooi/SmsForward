package com.hp.smsforward;

import android.content.Context;
import android.graphics.Color;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class LogFormat {
    private LogFormat() {
    }

    static String statusLabel(String status) {
        if (SmsRecord.STATUS_SENT.equals(status)) return "已发送";
        if (SmsRecord.STATUS_FAILED.equals(status)) return "发送失败";
        if (SmsRecord.STATUS_SENDING.equals(status)) return "发送中";
        return "待发送";
    }

    static int statusColor(Context context, String status) {
        if (SmsRecord.STATUS_SENT.equals(status)) return context.getColor(R.color.accent);
        if (SmsRecord.STATUS_FAILED.equals(status)) return context.getColor(R.color.danger);
        if (SmsRecord.STATUS_SENDING.equals(status) || SmsRecord.STATUS_PENDING.equals(status)) {
            return context.getColor(R.color.warn);
        }
        return context.getColor(R.color.muted);
    }

    static String time(long millis, String pattern) {
        return new SimpleDateFormat(pattern, Locale.CHINA).format(new Date(millis));
    }
}
