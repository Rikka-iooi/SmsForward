package com.hp.smsforward;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Patterns;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * User configuration. The password field is encrypted by {@link SecurePrefs} before storage.
 */
public final class Config {
    public static final String CHANNEL_EMAIL = "email";
    public static final String CHANNEL_WEBHOOK = "webhook";

    public static final String SECURITY_SSL = "ssl";
    public static final String SECURITY_STARTTLS = "starttls";

    public static final String PROVIDER_QQ = "qq";
    public static final String PROVIDER_163 = "163";
    public static final String PROVIDER_GMAIL = "gmail";
    public static final String PROVIDER_OUTLOOK = "outlook";
    public static final String PROVIDER_CUSTOM = "custom";

    private static final String PREFS = "sms_forward_config";
    private static final String KEY_PASSWORD = "smtp_password";

    public boolean enabled;
    public String channel = CHANNEL_EMAIL;
    public String provider = PROVIDER_QQ;
    public String smtpHost = "smtp.qq.com";
    public int smtpPort = 465;
    public String smtpSecurity = SECURITY_SSL;
    public String smtpUser = "";
    public String smtpPassword = "";
    public String recipients = "";
    public String webhookUrl = "";
    public boolean passwordReadError;

    public static Config load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Config config = new Config();
        config.enabled = prefs.getBoolean("enabled", false);
        config.channel = prefs.getString("channel", CHANNEL_EMAIL);
        config.provider = prefs.getString("provider", PROVIDER_QQ);
        config.smtpHost = prefs.getString("smtp_host", "smtp.qq.com");
        config.smtpPort = prefs.getInt("smtp_port", 465);
        config.smtpSecurity = prefs.getString("smtp_security", SECURITY_SSL);
        config.smtpUser = prefs.getString("smtp_user", "");
        config.recipients = prefs.getString("recipients", "");
        config.webhookUrl = prefs.getString("webhook_url", "");

        String encrypted = prefs.getString(KEY_PASSWORD, "");
        if (!TextUtils.isEmpty(encrypted)) {
            config.smtpPassword = SecurePrefs.decrypt(encrypted);
            config.passwordReadError = TextUtils.isEmpty(config.smtpPassword);
        }
        return config;
    }

    public static boolean hasStoredConfig(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.contains("smtp_host") || prefs.contains("webhook_url")
                || prefs.contains("enabled");
    }

    public void save(Context context) throws Exception {
        normalize();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean("enabled", enabled)
                .putString("channel", channel)
                .putString("provider", provider)
                .putString("smtp_host", smtpHost)
                .putInt("smtp_port", smtpPort)
                .putString("smtp_security", smtpSecurity)
                .putString("smtp_user", smtpUser)
                .putString("recipients", recipients)
                .putString("webhook_url", webhookUrl);
        if (!TextUtils.isEmpty(smtpPassword)) {
            editor.putString(KEY_PASSWORD, SecurePrefs.encrypt(smtpPassword));
            passwordReadError = false;
        }
        if (!editor.commit()) throw new IllegalStateException("配置写入失败");
    }

    public void clearPassword(Context context) {
        smtpPassword = "";
        passwordReadError = false;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_PASSWORD).apply();
    }

    public boolean hasPassword() {
        return !TextUtils.isEmpty(smtpPassword);
    }

    public Config copy() {
        Config copy = new Config();
        copy.enabled = enabled;
        copy.channel = channel;
        copy.provider = provider;
        copy.smtpHost = smtpHost;
        copy.smtpPort = smtpPort;
        copy.smtpSecurity = smtpSecurity;
        copy.smtpUser = smtpUser;
        copy.smtpPassword = smtpPassword;
        copy.recipients = recipients;
        copy.webhookUrl = webhookUrl;
        copy.passwordReadError = passwordReadError;
        return copy;
    }

    public List<String> recipientList() {
        String raw = TextUtils.isEmpty(recipients) ? smtpUser : recipients;
        Set<String> unique = new LinkedHashSet<>();
        if (!TextUtils.isEmpty(raw)) {
            for (String part : raw.split("[,;，；\\s]+")) {
                String value = part.trim();
                if (!value.isEmpty()) unique.add(value);
            }
        }
        return new ArrayList<>(unique);
    }

    public String targetSummary() {
        if (CHANNEL_WEBHOOK.equals(channel)) {
            try {
                URI uri = new URI(webhookUrl);
                return TextUtils.isEmpty(uri.getHost()) ? "Webhook" : "Webhook · " + uri.getHost();
            } catch (Exception e) {
                return "Webhook";
            }
        }
        List<String> recipientsList = recipientList();
        if (recipientsList.isEmpty()) return "邮箱未配置";
        if (recipientsList.size() == 1) return "邮箱 · " + recipientsList.get(0);
        return "邮箱 · " + recipientsList.get(0) + " 等 " + recipientsList.size() + " 个地址";
    }

    /**
     * Returns null when the active channel can forward, otherwise a user-facing error.
     */
    public String validateForForwarding() {
        if (CHANNEL_WEBHOOK.equals(channel)) {
            if (TextUtils.isEmpty(webhookUrl)) return "请填写 Webhook 地址";
            if (!isAllowedWebhookUrl(webhookUrl)) {
                return "Webhook 仅支持 HTTPS；本机调试可用 http://127.0.0.1";
            }
            return null;
        }

        if (TextUtils.isEmpty(smtpHost)) return "请填写 SMTP 服务器";
        if (smtpPort < 1 || smtpPort > 65535) return "SMTP 端口不正确";
        if (!SECURITY_SSL.equals(smtpSecurity) && !SECURITY_STARTTLS.equals(smtpSecurity)) {
            return "请选择 SSL/TLS 或 STARTTLS 加密";
        }
        if (TextUtils.isEmpty(smtpUser) || !Patterns.EMAIL_ADDRESS.matcher(smtpUser).matches()) {
            return "请填写正确的邮箱账号";
        }
        if (!hasPassword()) {
            return passwordReadError
                    ? "授权码读取失败，请重新输入"
                    : "请填写邮箱授权码或客户端专用密码";
        }
        List<String> recipientsList = recipientList();
        if (recipientsList.isEmpty()) return "请填写收件邮箱";
        for (String address : recipientsList) {
            if (!Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
                return "收件邮箱格式不正确：" + address;
            }
        }
        return null;
    }

    public static boolean isAllowedWebhookUrl(String value) {
        if (TextUtils.isEmpty(value)) return false;
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null) return false;
            if ("https".equalsIgnoreCase(scheme)) return true;
            if ("http".equalsIgnoreCase(scheme)) {
                return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
                        || "::1".equals(host);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static String securityLabel(String value) {
        return SECURITY_STARTTLS.equals(value) ? "STARTTLS" : "SSL/TLS";
    }

    public static String providerLabel(String value) {
        if (PROVIDER_QQ.equals(value)) return "QQ 邮箱";
        if (PROVIDER_163.equals(value)) return "163 邮箱";
        if (PROVIDER_GMAIL.equals(value)) return "Gmail";
        if (PROVIDER_OUTLOOK.equals(value)) return "Outlook";
        return "自定义 SMTP";
    }

    public String securityForDisplay() {
        return securityLabel(smtpSecurity);
    }

    private void normalize() {
        if (!CHANNEL_WEBHOOK.equals(channel)) channel = CHANNEL_EMAIL;
        smtpHost = trim(smtpHost);
        smtpUser = trim(smtpUser);
        recipients = trim(recipients);
        webhookUrl = trim(webhookUrl);
        if (!SECURITY_STARTTLS.equals(smtpSecurity)) {
            smtpSecurity = SECURITY_SSL;
        }
        provider = provider == null ? PROVIDER_CUSTOM : provider.toLowerCase(Locale.US);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
