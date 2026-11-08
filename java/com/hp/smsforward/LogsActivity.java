package com.hp.smsforward;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class LogsActivity extends android.app.Activity {
    private Db db;
    private ListView listLogs;
    private TextView emptyLogs;
    private final List<SmsRecord> records = new ArrayList<>();
    private LogAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        db = new Db(this);
        listLogs = findViewById(R.id.listLogs);
        emptyLogs = findViewById(R.id.emptyLogs);
        adapter = new LogAdapter();
        listLogs.setAdapter(adapter);
        listLogs.setOnItemClickListener((parent, view, position, id) ->
                showRecord(records.get(position)));

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnClear).setOnClickListener(v -> confirmClear());
        refresh();
    }

    private void refresh() {
        records.clear();
        records.addAll(db.recent(200));
        adapter.notifyDataSetChanged();
        boolean empty = records.isEmpty();
        emptyLogs.setVisibility(empty ? View.VISIBLE : View.GONE);
        listLogs.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void showRecord(SmsRecord record) {
        String status = LogFormat.statusLabel(record.status);
        if (record.attempts > 0) status += "（已尝试 " + record.attempts + " 次）";
        StringBuilder message = new StringBuilder();
        message.append("发件人：").append(TextUtils.isEmpty(record.sender) ? "未知号码" : record.sender)
                .append("\n接收时间：").append(LogFormat.time(record.receivedAt, "yyyy-MM-dd HH:mm:ss"))
                .append("\n状态：").append(status);
        if (!TextUtils.isEmpty(record.error)) {
            message.append("\n失败原因：").append(record.error);
        }
        message.append("\n\n──────────\n\n").append(record.body);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("短信详情")
                .setMessage(message.toString())
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制正文", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("短信正文", record.body));
                    Toast.makeText(this, "正文已复制", Toast.LENGTH_SHORT).show();
                });
        if (SmsRecord.STATUS_FAILED.equals(record.status)) {
            builder.setNegativeButton("重试", (dialog, which) -> {
                db.requeue(record.id);
                ForwardJobService.schedule(this);
                refresh();
                Toast.makeText(this, "已加入重试队列", Toast.LENGTH_SHORT).show();
            });
        }
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            TextView text = dialog.findViewById(android.R.id.message);
            if (text != null) {
                text.setTextIsSelectable(true);
                text.setTextSize(13);
            }
        });
        dialog.show();
    }

    private void confirmClear() {
        if (records.isEmpty()) {
            Toast.makeText(this, "暂无记录", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空转发记录？")
                .setMessage("只会删除本机保存的短信记录和发送状态，不会影响邮箱或 Webhook 中已经发送的内容。")
                .setPositiveButton("清空", (dialog, which) -> {
                    db.clearAll();
                    refresh();
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (db != null) refresh();
    }

    @Override
    protected void onDestroy() {
        if (db != null) db.close();
        super.onDestroy();
    }

    private final class LogAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return records.size();
        }

        @Override
        public SmsRecord getItem(int position) {
            return records.get(position);
        }

        @Override
        public long getItemId(int position) {
            return records.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = getLayoutInflater().inflate(R.layout.item_log, parent, false);
            }
            SmsRecord record = records.get(position);
            TextView status = row.findViewById(R.id.logStatus);
            TextView time = row.findViewById(R.id.logTime);
            TextView sender = row.findViewById(R.id.logSender);
            TextView preview = row.findViewById(R.id.logPreview);
            TextView error = row.findViewById(R.id.logError);

            status.setText(LogFormat.statusLabel(record.status));
            status.setTextColor(LogFormat.statusColor(LogsActivity.this, record.status));
            time.setText(LogFormat.time(record.receivedAt, "MM-dd HH:mm"));
            sender.setText(TextUtils.isEmpty(record.sender) ? "未知号码" : record.sender);
            sender.setTypeface(null, Typeface.BOLD);
            preview.setText(TextUtils.isEmpty(record.body) ? "（空短信）" : record.preview(110));
            if (!TextUtils.isEmpty(record.error) && !SmsRecord.STATUS_SENT.equals(record.status)) {
                error.setText(record.error);
                error.setVisibility(View.VISIBLE);
            } else {
                error.setVisibility(View.GONE);
            }
            return row;
        }
    }
}
