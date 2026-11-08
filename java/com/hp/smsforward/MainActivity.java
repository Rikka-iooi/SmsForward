package com.hp.smsforward;

import android.Manifest;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends android.app.Activity {
    private static final int REQ_SMS = 1001;

    private static final String[] PROVIDER_VALUES = {
            Config.PROVIDER_QQ, Config.PROVIDER_163, Config.PROVIDER_GMAIL,
            Config.PROVIDER_OUTLOOK, Config.PROVIDER_CUSTOM
    };
    private static final String[] PROVIDER_LABELS = {
            "QQ 邮箱", "163 邮箱", "Gmail", "Outlook", "自定义 SMTP"
    };
    private static final String[] SECURITY_VALUES = {
            Config.SECURITY_SSL, Config.SECURITY_STARTTLS
    };
    private static final String[] SECURITY_LABELS = {
            "SSL/TLS（推荐）", "STARTTLS"
    };

    private Config config;
    private Db db;
    private ExecutorService executor;
    private String channel;
    private boolean suppressSwitch;
    private boolean enableAfterPermission;
    private boolean ignoreProviderCallback = true;
    private boolean passwordVisible;

    private Switch switchEnabled;
    private TextView statusPill;
    private TextView statusDetail;
    private TextView statSent;
    private TextView statPending;
    private TextView statFailed;
    private LinearLayout permissionBanner;
    private TextView permissionTitle;
    private TextView permissionHint;
    private TextView btnGrant;
    private TextView tabEmail;
    private TextView tabWebhook;
    private LinearLayout emailPanel;
    private LinearLayout webhookPanel;
    private Spinner spinnerProvider;
    private Spinner spinnerSecurity;
    private EditText editHost;
    private EditText editPort;
    private EditText editUser;
    private EditText editPass;
    private EditText editRecipients;
    private EditText editWebhook;
    private TextView hintProvider;
    private TextView btnTogglePass;
    private Button btnSave;
    private Button btnTest;
    private TextView configState;
    private LinearLayout logContainer;
    private TextView emptyLogs;
    private TextView btnAllLogs;
    private TextView btnAutostart;
    private TextView btnBattery;
    private TextView batteryState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        config = Config.load(this);
        channel = config.channel;
        db = new Db(this);
        executor = Executors.newSingleThreadExecutor();

        bindViews();
        setupSpinners();
        populateForm();
        setupListeners();
        updateAll();
    }

    private void bindViews() {
        switchEnabled = findViewById(R.id.switchEnabled);
        statusPill = findViewById(R.id.statusPill);
        statusDetail = findViewById(R.id.statusDetail);
        statSent = findViewById(R.id.statSent);
        statPending = findViewById(R.id.statPending);
        statFailed = findViewById(R.id.statFailed);
        permissionBanner = findViewById(R.id.permissionBanner);
        permissionTitle = findViewById(R.id.permissionTitle);
        permissionHint = findViewById(R.id.permissionHint);
        btnGrant = findViewById(R.id.btnGrant);
        tabEmail = findViewById(R.id.tabEmail);
        tabWebhook = findViewById(R.id.tabWebhook);
        emailPanel = findViewById(R.id.emailPanel);
        webhookPanel = findViewById(R.id.webhookPanel);
        spinnerProvider = findViewById(R.id.spinnerProvider);
        spinnerSecurity = findViewById(R.id.spinnerSecurity);
        editHost = findViewById(R.id.editHost);
        editPort = findViewById(R.id.editPort);
        editUser = findViewById(R.id.editUser);
        editPass = findViewById(R.id.editPass);
        editRecipients = findViewById(R.id.editRecipients);
        editWebhook = findViewById(R.id.editWebhook);
        hintProvider = findViewById(R.id.hintProvider);
        btnTogglePass = findViewById(R.id.btnTogglePass);
        btnSave = findViewById(R.id.btnSave);
        btnTest = findViewById(R.id.btnTest);
        configState = findViewById(R.id.configState);
        logContainer = findViewById(R.id.logContainer);
        emptyLogs = findViewById(R.id.emptyLogs);
        btnAllLogs = findViewById(R.id.btnAllLogs);
        btnAutostart = findViewById(R.id.btnAutostart);
        btnBattery = findViewById(R.id.btnBattery);
        batteryState = findViewById(R.id.batteryState);
    }

    private void setupSpinners() {
        ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, PROVIDER_LABELS);
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProvider.setAdapter(providerAdapter);

        ArrayAdapter<String> securityAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, SECURITY_LABELS);
        securityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSecurity.setAdapter(securityAdapter);
    }

    private void populateForm() {
        editHost.setText(config.smtpHost);
        editPort.setText(config.smtpPort > 0 ? String.valueOf(config.smtpPort) : "");
        editUser.setText(config.smtpUser);
        editRecipients.setText(config.recipients);
        editWebhook.setText(config.webhookUrl);

        int providerIndex = indexOf(PROVIDER_VALUES, config.provider);
        if (providerIndex < 0) providerIndex = PROVIDER_VALUES.length - 1;
        ignoreProviderCallback = true;
        spinnerProvider.setSelection(providerIndex);
        spinnerSecurity.setSelection(Config.SECURITY_STARTTLS.equals(config.smtpSecurity) ? 1 : 0);
        spinnerProvider.post(() -> ignoreProviderCallback = false);

        editPass.setText("");
        updatePasswordHint();
        updateProviderHint(config.provider);
        selectChannelUi();
        if (Config.hasStoredConfig(this)) updateConfigState();

        suppressSwitch = true;
        switchEnabled.setChecked(config.enabled);
        suppressSwitch = false;
    }

    private void setupListeners() {
        spinnerProvider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (ignoreProviderCallback) return;
                String selected = PROVIDER_VALUES[position];
                if (selected.equals(config.provider)) {
                    updateProviderHint(selected);
                    return;
                }
                applyProvider(selected);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        tabEmail.setOnClickListener(v -> switchChannel(Config.CHANNEL_EMAIL));
        tabWebhook.setOnClickListener(v -> switchChannel(Config.CHANNEL_WEBHOOK));

        switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressSwitch) return;
            if (isChecked) {
                enableForwarding();
            } else {
                disableForwarding();
            }
        });

        btnSave.setOnClickListener(v -> {
            if (saveForm(true)) {
                Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
            }
        });
        btnTest.setOnClickListener(v -> testForwarding());
        btnTogglePass.setOnClickListener(v -> togglePasswordVisibility());
        btnGrant.setOnClickListener(v -> {
            if (hasSmsPermission() && !hasSmsAppOp()) {
                showSmsPermissionHelp();
            } else {
                requestSmsPermission();
            }
        });
        btnAllLogs.setOnClickListener(v -> startActivity(new Intent(this, LogsActivity.class)));
        btnAutostart.setOnClickListener(v -> openAutostartSettings());
        btnBattery.setOnClickListener(v -> openBatterySettings());
    }

    private void applyProvider(String provider) {
        config.provider = provider;
        spinnerSecurity.setSelection(Config.SECURITY_STARTTLS.equals(config.smtpSecurity) ? 1 : 0);
        switch (provider) {
            case Config.PROVIDER_QQ:
                editHost.setText("smtp.qq.com");
                editPort.setText("465");
                spinnerSecurity.setSelection(0);
                break;
            case Config.PROVIDER_163:
                editHost.setText("smtp.163.com");
                editPort.setText("465");
                spinnerSecurity.setSelection(0);
                break;
            case Config.PROVIDER_GMAIL:
                editHost.setText("smtp.gmail.com");
                editPort.setText("465");
                spinnerSecurity.setSelection(0);
                break;
            case Config.PROVIDER_OUTLOOK:
                editHost.setText("smtp.office365.com");
                editPort.setText("587");
                spinnerSecurity.setSelection(1);
                break;
            default:
                break;
        }
        updateProviderHint(provider);
        updatePasswordHint();
    }

    private void updateProviderHint(String provider) {
        switch (provider) {
            case Config.PROVIDER_QQ:
                hintProvider.setText("QQ 邮箱：网页版「设置 → 账号与安全 → POP3/IMAP/SMTP 服务」开启 SMTP，并生成 16 位授权码。密码框填授权码，不是 QQ 密码。");
                break;
            case Config.PROVIDER_163:
                hintProvider.setText("163 邮箱：网页版「设置 → POP3/SMTP/IMAP」开启 SMTP 服务并获取授权码，密码框填授权码。");
                break;
            case Config.PROVIDER_GMAIL:
                hintProvider.setText("Gmail：开启两步验证后生成「应用专用密码」，用它代替邮箱密码。国内移动网络可能无法直连。");
                break;
            case Config.PROVIDER_OUTLOOK:
                hintProvider.setText("Outlook：个人账户使用应用密码；Microsoft 365 账户需管理员允许 SMTP AUTH。");
                break;
            default:
                hintProvider.setText("自定义 SMTP：填写服务商提供的主机、端口和加密方式，密码填客户端专用密码或授权码。");
                break;
        }
    }

    private void updatePasswordHint() {
        if (config.hasPassword()) {
            editPass.setHint("已保存，留空不修改");
        } else if (Config.PROVIDER_GMAIL.equals(config.provider)
                || Config.PROVIDER_OUTLOOK.equals(config.provider)) {
            editPass.setHint("应用专用密码");
        } else if (Config.PROVIDER_QQ.equals(config.provider)) {
            editPass.setHint("QQ邮箱填16位授权码");
        } else {
            editPass.setHint("授权码 / 客户端专用密码");
        }
    }

    private void switchChannel(String target) {
        if (target.equals(channel)) return;
        persistSilently();
        channel = target;
        config.channel = target;
        selectChannelUi();
        saveForm(false);
        updateStatus();
    }

    private void selectChannelUi() {
        boolean email = !Config.CHANNEL_WEBHOOK.equals(channel);
        emailPanel.setVisibility(email ? View.VISIBLE : View.GONE);
        webhookPanel.setVisibility(email ? View.GONE : View.VISIBLE);

        tabEmail.setBackgroundResource(email ? R.drawable.bg_segment_selected : 0);
        tabWebhook.setBackgroundResource(email ? 0 : R.drawable.bg_segment_selected);
        tabEmail.setTextColor(getColor(email ? R.color.accent : R.color.muted));
        tabWebhook.setTextColor(getColor(email ? R.color.muted : R.color.accent));
        tabEmail.setTypeface(null, email ? Typeface.BOLD : Typeface.NORMAL);
        tabWebhook.setTypeface(null, email ? Typeface.NORMAL : Typeface.BOLD);
    }

    private void enableForwarding() {
        if (!saveForm(false)) {
            setSwitch(false);
            return;
        }
        String error = config.validateForForwarding();
        if (error != null) {
            setSwitch(false);
            showDialog("还不能开启", error);
            return;
        }
        if (!hasSmsPermission()) {
            enableAfterPermission = true;
            setSwitch(false);
            requestSmsPermission();
            return;
        }
        if (!hasSmsAppOp()) {
            enableAfterPermission = true;
            setSwitch(false);
            showSmsPermissionHelp();
            return;
        }
        config.enabled = true;
        if (!saveForm(false)) {
            config.enabled = false;
            setSwitch(false);
            return;
        }
        ForwardJobService.schedule(this);
        setSwitch(true);
        updateAll();
        Toast.makeText(this, "已开启，新短信会自动转发", Toast.LENGTH_SHORT).show();
    }

    private void disableForwarding() {
        enableAfterPermission = false;
        config.enabled = false;
        saveForm(false);
        ForwardJobService.cancel(this);
        setSwitch(false);
        updateAll();
        Toast.makeText(this, "已暂停转发", Toast.LENGTH_SHORT).show();
    }

    private boolean saveForm(boolean showErrors) {
        collectForm();
        try {
            config.save(this);
        } catch (Exception e) {
            if (showErrors) {
                showDialog("保存失败", "无法安全保存配置：" + safeMessage(e));
            }
            return false;
        }
        editPass.setText("");
        updatePasswordHint();
        updateConfigState();

        if (config.enabled) {
            String error = config.validateForForwarding();
            if (error != null) {
                config.enabled = false;
                try {
                    config.save(this);
                } catch (Exception ignored) {
                    // In-memory state is still disabled.
                }
                ForwardJobService.cancel(this);
                setSwitch(false);
                updateStatus();
                showDialog("已暂停转发", "配置不完整：" + error);
            }
        }
        updateStatus();
        return true;
    }

    private void collectForm() {
        config.channel = channel;
        int providerIndex = spinnerProvider.getSelectedItemPosition();
        if (providerIndex >= 0 && providerIndex < PROVIDER_VALUES.length) {
            config.provider = PROVIDER_VALUES[providerIndex];
        }
        int securityIndex = spinnerSecurity.getSelectedItemPosition();
        config.smtpSecurity = securityIndex == 1
                ? Config.SECURITY_STARTTLS : Config.SECURITY_SSL;
        config.smtpHost = editHost.getText().toString().trim();
        String port = editPort.getText().toString().trim();
        try {
            config.smtpPort = Integer.parseInt(port);
        } catch (NumberFormatException e) {
            config.smtpPort = 0;
        }
        config.smtpUser = editUser.getText().toString().trim();
        config.recipients = editRecipients.getText().toString().trim();
        config.webhookUrl = editWebhook.getText().toString().trim();

        String enteredPassword = editPass.getText().toString();
        if (!enteredPassword.isEmpty()) {
            config.smtpPassword = enteredPassword;
            config.passwordReadError = false;
        }
    }

    private void persistSilently() {
        collectForm();
        try {
            config.save(this);
        } catch (Exception ignored) {
            // The next explicit save will surface an error.
        }
    }

    private void updateAll() {
        updatePermissionUi();
        updateStatus();
        updateStats();
        renderRecentLogs();
        updateBatteryState();
    }

    private void updatePermissionUi() {
        boolean granted = hasSmsPermission();
        boolean appOpAllowed = hasSmsAppOp();
        boolean ready = granted && appOpAllowed;
        permissionBanner.setVisibility(ready ? View.GONE : View.VISIBLE);
        if (granted && !appOpAllowed) {
            permissionTitle.setText("短信权限受限");
            permissionHint.setText("系统拦截了短信权限，点右侧去设置");
            btnGrant.setText("去设置");
        } else {
            permissionTitle.setText("需要短信权限");
            if (Build.VERSION.SDK_INT >= 35) {
                permissionHint.setText("Android 15/16 侧载应用需先允许“受限制的设置”");
            } else {
                permissionHint.setText("只读取新短信，不读取历史记录");
            }
            btnGrant.setText("去授权");
        }
    }

    private void updateStatus() {
        boolean granted = hasSmsPermission();
        boolean appOpAllowed = hasSmsAppOp();
        String error = config.validateForForwarding();
        if (!config.enabled) {
            statusPill.setText("已暂停");
            statusPill.setBackgroundResource(R.drawable.bg_pill_idle);
            statusPill.setTextColor(getColor(R.color.muted));
            statusDetail.setText("开启后，新收到的短信会自动转发");
        } else if (!granted) {
            statusPill.setText("待授权");
            statusPill.setBackgroundResource(R.drawable.bg_pill_warn);
            statusPill.setTextColor(getColor(R.color.warn));
            statusDetail.setText("缺少短信权限，点下方“去授权”");
        } else if (!appOpAllowed) {
            statusPill.setText("权限受限");
            statusPill.setBackgroundResource(R.drawable.bg_pill_warn);
            statusPill.setTextColor(getColor(R.color.warn));
            statusDetail.setText("系统拦截了短信权限，点下方“去设置”");
        } else if (error != null) {
            statusPill.setText("配置待完善");
            statusPill.setBackgroundResource(R.drawable.bg_pill_warn);
            statusPill.setTextColor(getColor(R.color.warn));
            statusDetail.setText(error);
        } else {
            statusPill.setText("监听中");
            statusPill.setBackgroundResource(R.drawable.bg_pill_active);
            statusPill.setTextColor(getColor(R.color.accent));
            statusDetail.setText("新短信将转发至 " + config.targetSummary());
        }
        setSwitch(config.enabled);
        switchEnabled.setEnabled(true);
    }

    private void updateStats() {
        int sent = db.countStatus(SmsRecord.STATUS_SENT);
        int pending = db.countPending();
        int failed = db.countStatus(SmsRecord.STATUS_FAILED);
        statSent.setText(String.valueOf(sent));
        statPending.setText(String.valueOf(pending));
        statFailed.setText(String.valueOf(failed));
        statFailed.setTextColor(getColor(failed > 0 ? R.color.danger : R.color.text));
    }

    private void renderRecentLogs() {
        List<SmsRecord> records = db.recent(5);
        logContainer.removeAllViews();
        emptyLogs.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
        for (SmsRecord record : records) {
            View row = getLayoutInflater().inflate(R.layout.item_log, logContainer, false);
            bindLogRow(row, record);
            row.setOnClickListener(v -> showRecordDialog(record.id));
            logContainer.addView(row);
        }
    }

    private void bindLogRow(View row, SmsRecord record) {
        TextView status = row.findViewById(R.id.logStatus);
        TextView time = row.findViewById(R.id.logTime);
        TextView sender = row.findViewById(R.id.logSender);
        TextView preview = row.findViewById(R.id.logPreview);
        TextView error = row.findViewById(R.id.logError);

        status.setText(LogFormat.statusLabel(record.status));
        status.setTextColor(LogFormat.statusColor(this, record.status));
        time.setText(LogFormat.time(record.receivedAt, "MM-dd HH:mm"));
        sender.setText(TextUtils.isEmpty(record.sender) ? "未知号码" : record.sender);
        preview.setText(TextUtils.isEmpty(record.body) ? "（空短信）" : record.preview(90));

        if (!TextUtils.isEmpty(record.error) && !SmsRecord.STATUS_SENT.equals(record.status)) {
            error.setText(record.error);
            error.setVisibility(View.VISIBLE);
        } else {
            error.setVisibility(View.GONE);
        }
    }

    private void showRecordDialog(long id) {
        SmsRecord record = db.get(id);
        if (record == null) return;
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
                db.requeue(id);
                ForwardJobService.schedule(this);
                updateStats();
                renderRecentLogs();
                Toast.makeText(this, config.enabled ? "已加入重试队列" : "已加入队列，请先开启转发",
                        Toast.LENGTH_SHORT).show();
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

    private void testForwarding() {
        if (!saveForm(false)) return;
        String error = config.validateForForwarding();
        if (error != null) {
            showDialog("配置不完整", error);
            return;
        }
        final Config snapshot = config.copy();
        btnTest.setEnabled(false);
        btnTest.setText("发送中…");
        executor.execute(() -> {
            String failure = null;
            try {
                Forwarder.sendTest(snapshot, Forwarder.testText());
            } catch (Exception e) {
                failure = safeMessage(e);
            }
            final String result = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                btnTest.setEnabled(true);
                btnTest.setText("发送测试");
                if (result == null) {
                    showDialog("测试成功", Config.CHANNEL_WEBHOOK.equals(snapshot.channel)
                            ? "Webhook 已返回成功状态。"
                            : "测试邮件已交给 SMTP 服务器，请检查收件箱和垃圾邮件。");
                } else {
                    showDialog("发送失败", result);
                }
            });
        });
    }

    private void togglePasswordVisibility() {
        passwordVisible = !passwordVisible;
        int selection = editPass.getSelectionStart();
        if (passwordVisible) {
            editPass.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        } else {
            editPass.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        editPass.setSelection(Math.max(0, Math.min(selection, editPass.getText().length())));
        btnTogglePass.setText(passwordVisible ? "隐藏" : "显示");
    }

    private boolean hasSmsPermission() {
        return checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Android 15/16 can leave the runtime permission granted but the SMS app-op ignored for
     * sideloaded apps. MIUI has a similar per-UID switch. Checking the app-op catches both cases.
     */
    private boolean hasSmsAppOp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true;
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            if (appOps == null) return true;
            int mode = appOps.checkOpNoThrow("android:receive_sms", Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT;
        } catch (Exception e) {
            return true;
        }
    }

    private boolean canReceiveSms() {
        return hasSmsPermission() && hasSmsAppOp();
    }

    private void requestSmsPermission() {
        if (hasSmsPermission()) {
            if (!hasSmsAppOp()) {
                showSmsPermissionHelp();
            } else {
                updateAll();
            }
            return;
        }
        requestPermissions(new String[]{Manifest.permission.RECEIVE_SMS}, REQ_SMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_SMS) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        if (!granted) {
            enableAfterPermission = false;
            config.enabled = false;
            persistSilently();
            setSwitch(false);
            updateAll();
            showSmsPermissionHelp();
            return;
        }

        if (!hasSmsAppOp()) {
            // Android 15/16 or an OEM policy is still blocking the app-op.
            config.enabled = false;
            persistSilently();
            setSwitch(false);
            updateAll();
            showSmsPermissionHelp();
            return;
        }

        if (enableAfterPermission) {
            config.enabled = true;
            if (saveForm(false)) {
                setSwitch(true);
                ForwardJobService.schedule(this);
                Toast.makeText(this, "权限已授予，转发已开启", Toast.LENGTH_SHORT).show();
            } else {
                config.enabled = false;
                setSwitch(false);
            }
            enableAfterPermission = false;
        }
        if (config.enabled && db.countPending() > 0) {
            ForwardJobService.schedule(this);
        }
        updateAll();
    }

    private void showSmsPermissionHelp() {
        if (isFinishing() || isDestroyed()) return;
        String message;
        if (Build.VERSION.SDK_INT >= 35) {
            message = "Android 15/16 对侧载应用的短信权限增加了保护。\n\n"
                    + "1. 打开“应用信息”\n"
                    + "2. 点右上角 ⋮（部分机型为“更多”）\n"
                    + "3. 选择“允许受限制的设置”\n"
                    + "4. 进入“权限 → 短信”，选择“始终允许”\n\n"
                    + "如果找不到该菜单，请先在系统设置中允许本应用的“安装未知应用”，再回来操作。\n\n"
                    + "设置完成后回到 App，会自动继续开启转发。";
        } else {
            message = "请在“应用信息 → 权限 → 短信”中选择“始终允许”。\n\n"
                    + "部分系统还会在权限管理中单独限制短信，请一并允许。\n\n"
                    + "设置完成后回到 App 即可。";
        }
        new AlertDialog.Builder(this)
                .setTitle("开启短信权限")
                .setMessage(message)
                .setPositiveButton("打开应用信息", (dialog, which) -> openAppSettings())
                .setNegativeButton("取消", null)
                .show();
    }

    private void openAutostartSettings() {
        Toast.makeText(this, "请允许“短信转发”自启动", Toast.LENGTH_LONG).show();
        if (!DeviceSettings.openAutoStart(this)) {
            Toast.makeText(this, "未找到厂商自启动页面，请在应用信息中设置", Toast.LENGTH_LONG).show();
            DeviceSettings.openAppDetails(this);
        }
    }

    private void openBatterySettings() {
        DeviceSettings.openBatterySettings(this);
        Toast.makeText(this, "如系统有“省电策略”，请选择“无限制”", Toast.LENGTH_LONG).show();
    }

    private void openAppSettings() {
        DeviceSettings.openAppDetails(this);
    }

    private void updateBatteryState() {
        boolean ignoring = DeviceSettings.isIgnoringBatteryOptimizations(this);
        if (ignoring) {
            batteryState.setText("电池优化：已允许后台运行");
            batteryState.setTextColor(getColor(R.color.accent));
        } else {
            batteryState.setText("电池优化：未设置，系统可能延迟转发");
            batteryState.setTextColor(getColor(R.color.warn));
        }
    }

    private void updateConfigState() {
        configState.setText("已保存 · " + config.targetSummary());
    }

    private void setSwitch(boolean checked) {
        suppressSwitch = true;
        switchEnabled.setChecked(checked);
        suppressSwitch = false;
    }

    private void showDialog(String title, String message) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = throwable.getClass().getSimpleName();
        }
        return message.length() > 1200 ? message.substring(0, 1200) : message;
    }

    private static int indexOf(String[] values, String value) {
        if (value == null) return -1;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) return i;
        }
        return -1;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (db == null) db = new Db(this);

        if (enableAfterPermission && canReceiveSms() && !config.enabled) {
            config.enabled = true;
            if (saveForm(false)) {
                setSwitch(true);
                ForwardJobService.schedule(this);
                Toast.makeText(this, "权限已就绪，转发已开启", Toast.LENGTH_SHORT).show();
            } else {
                config.enabled = false;
                setSwitch(false);
            }
            enableAfterPermission = false;
        }

        if (config.enabled && canReceiveSms() && db.countPending() > 0) {
            ForwardJobService.schedule(this);
        }
        updateAll();
    }

    @Override
    protected void onPause() {
        persistSilently();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (executor != null) executor.shutdownNow();
        if (db != null) db.close();
        super.onDestroy();
    }
}
