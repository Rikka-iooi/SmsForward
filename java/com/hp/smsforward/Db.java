package com.hp.smsforward;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Local-only store for received SMS and delivery state. Nothing is uploaded except by the
 * configured forwarder.
 */
public final class Db extends SQLiteOpenHelper {
    private static final String NAME = "sms_forward.db";
    private static final int VERSION = 1;
    private static final long THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000;

    public Db(Context context) {
        super(context.getApplicationContext(), NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "received_at INTEGER NOT NULL," +
                "sender TEXT NOT NULL," +
                "body TEXT NOT NULL," +
                "status TEXT NOT NULL," +
                "attempts INTEGER NOT NULL DEFAULT 0," +
                "error TEXT," +
                "channel TEXT," +
                "sent_at INTEGER)");
        db.execSQL("CREATE INDEX idx_messages_status ON messages(status, id)");
        db.execSQL("CREATE INDEX idx_messages_time ON messages(received_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the initial schema.
    }

    public long insert(String sender, String body, long receivedAt) {
        ContentValues values = new ContentValues();
        values.put("received_at", receivedAt);
        values.put("sender", sender == null ? "" : sender);
        values.put("body", body == null ? "" : body);
        values.put("status", SmsRecord.STATUS_PENDING);
        values.put("attempts", 0);
        return getWritableDatabase().insertOrThrow("messages", null, values);
    }

    public boolean isDuplicate(String sender, String body, long now) {
        String[] args = {sender == null ? "" : sender, body == null ? "" : body,
                String.valueOf(now - 5000L)};
        try (Cursor cursor = getReadableDatabase().query("messages", new String[]{"id"},
                "sender=? AND body=? AND received_at>=?", args, null, null, "id DESC", "1")) {
            return cursor.moveToFirst();
        }
    }

    public List<SmsRecord> pending(int limit) {
        List<SmsRecord> out = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("messages", null,
                "status=?", new String[]{SmsRecord.STATUS_PENDING},
                null, null, "id ASC", String.valueOf(limit))) {
            while (cursor.moveToNext()) out.add(read(cursor));
        }
        return out;
    }

    public SmsRecord get(long id) {
        try (Cursor cursor = getReadableDatabase().query("messages", null,
                "id=?", new String[]{String.valueOf(id)}, null, null, null, "1")) {
            return cursor.moveToFirst() ? read(cursor) : null;
        }
    }

    /**
     * Atomically takes ownership of a pending row so a job and receiver cannot send it twice.
     */
    public boolean claim(long id) {
        ContentValues values = new ContentValues();
        values.put("status", SmsRecord.STATUS_SENDING);
        return getWritableDatabase().update("messages", values,
                "id=? AND status=?", new String[]{String.valueOf(id), SmsRecord.STATUS_PENDING}) == 1;
    }

    public void markSent(long id, String channel) {
        ContentValues values = new ContentValues();
        values.put("status", SmsRecord.STATUS_SENT);
        values.put("sent_at", System.currentTimeMillis());
        values.put("channel", channel);
        values.putNull("error");
        getWritableDatabase().update("messages", values, "id=?",
                new String[]{String.valueOf(id)});
    }

    public void markAttemptFailed(long id, int maxAttempts, String error) {
        String safeError = error == null ? "未知错误" : error;
        if (safeError.length() > 1200) safeError = safeError.substring(0, 1200);
        getWritableDatabase().execSQL(
                "UPDATE messages SET attempts=attempts+1, " +
                        "status=CASE WHEN attempts+1>=? THEN 'failed' ELSE 'pending' END, " +
                        "error=? WHERE id=?",
                new Object[]{maxAttempts, safeError, id});
    }

    public void markFailed(long id, String error) {
        String safeError = error == null ? "未知错误" : error;
        if (safeError.length() > 1200) safeError = safeError.substring(0, 1200);
        ContentValues values = new ContentValues();
        values.put("status", SmsRecord.STATUS_FAILED);
        values.put("error", safeError);
        getWritableDatabase().update("messages", values, "id=?",
                new String[]{String.valueOf(id)});
    }

    public void resetStaleSending(long olderThanMs) {
        getWritableDatabase().execSQL(
                "UPDATE messages SET status='pending' WHERE status='sending' AND received_at<?",
                new Object[]{olderThanMs});
    }

    public boolean hasPending() {
        return countStatus(SmsRecord.STATUS_PENDING) > 0;
    }

    public int countStatus(String status) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM messages WHERE status=?", new String[]{status})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public int countPending() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM messages WHERE status IN ('pending','sending')", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public List<SmsRecord> recent(int limit) {
        List<SmsRecord> out = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("messages", null,
                null, null, null, null, "received_at DESC, id DESC", String.valueOf(limit))) {
            while (cursor.moveToNext()) out.add(read(cursor));
        }
        return out;
    }

    public void requeue(long id) {
        ContentValues values = new ContentValues();
        values.put("status", SmsRecord.STATUS_PENDING);
        values.put("attempts", 0);
        values.putNull("error");
        getWritableDatabase().update("messages", values,
                "id=? AND status=?", new String[]{String.valueOf(id), SmsRecord.STATUS_FAILED});
    }

    public void clearAll() {
        getWritableDatabase().delete("messages", null, null);
    }

    public void prune() {
        long cutoff = System.currentTimeMillis() - THIRTY_DAYS_MS;
        SQLiteDatabase db = getWritableDatabase();
        db.delete("messages", "status!='pending' AND status!='sending' AND received_at<?",
                new String[]{String.valueOf(cutoff)});
        db.execSQL("DELETE FROM messages WHERE status NOT IN ('pending','sending') AND id NOT IN (" +
                "SELECT id FROM messages WHERE status NOT IN ('pending','sending') " +
                "ORDER BY id DESC LIMIT 500)");
    }

    private static SmsRecord read(Cursor cursor) {
        long id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
        long receivedAt = cursor.getLong(cursor.getColumnIndexOrThrow("received_at"));
        String sender = cursor.getString(cursor.getColumnIndexOrThrow("sender"));
        String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));
        String status = cursor.getString(cursor.getColumnIndexOrThrow("status"));
        int attempts = cursor.getInt(cursor.getColumnIndexOrThrow("attempts"));
        String error = cursor.getString(cursor.getColumnIndexOrThrow("error"));
        String channel = cursor.getString(cursor.getColumnIndexOrThrow("channel"));
        int sentIndex = cursor.getColumnIndexOrThrow("sent_at");
        Long sentAt = cursor.isNull(sentIndex) ? null : cursor.getLong(sentIndex);
        return new SmsRecord(id, receivedAt, sender, body, status, attempts, error, channel, sentAt);
    }
}
