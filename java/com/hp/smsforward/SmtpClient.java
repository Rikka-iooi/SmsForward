package com.hp.smsforward;

import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A deliberately small SMTP client: implicit TLS (465) and STARTTLS (587), AUTH LOGIN with an
 * AUTH PLAIN fallback, one UTF-8 plain-text body. No third-party mail libraries, no message data
 * leaves the device except through the configured SMTP server.
 */
final class SmtpClient {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private final Config config;
    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;

    SmtpClient(Config config) {
        this.config = config;
    }

    void send(String subject, String body) throws Exception {
        String error = config.validateForForwarding();
        if (error != null) throw new SmtpException(error);

        try {
            connect();
            try {
                ehlo();
                if (Config.SECURITY_STARTTLS.equals(config.smtpSecurity)) {
                    startTls();
                    ehlo();
                }
                authenticate();
                mailFrom();
                recipients();
                data(buildMessage(subject, body));
                quitQuietly();
            } finally {
                closeQuietly();
            }
        } catch (SmtpException e) {
            throw e;
        } catch (UnknownHostException e) {
            throw new SmtpException("找不到 SMTP 服务器，请检查服务器地址", e);
        } catch (SocketTimeoutException e) {
            throw new SmtpException("连接 SMTP 服务器超时，请检查网络、端口和加密方式", e);
        } catch (SSLException e) {
            throw new SmtpException("TLS 连接失败，请确认端口与加密方式匹配（SSL 常用 465，STARTTLS 常用 587）", e);
        } catch (ConnectException e) {
            throw new SmtpException("无法连接 SMTP 服务器，请检查网络、端口和服务器地址", e);
        } catch (IOException e) {
            throw new SmtpException("与 SMTP 服务器通信失败：" + safeMessage(e), e);
        } finally {
            closeQuietly();
        }
    }

    private void connect() throws Exception {
        try {
            if (Config.SECURITY_SSL.equals(config.smtpSecurity)) {
                SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
                ssl.connect(new InetSocketAddress(config.smtpHost, config.smtpPort), CONNECT_TIMEOUT_MS);
                ssl.setSoTimeout(READ_TIMEOUT_MS);
                enableHostnameVerification(ssl);
                ssl.startHandshake();
                socket = ssl;
            } else {
                Socket plain = new Socket();
                plain.connect(new InetSocketAddress(config.smtpHost, config.smtpPort), CONNECT_TIMEOUT_MS);
                plain.setSoTimeout(READ_TIMEOUT_MS);
                socket = plain;
            }
            openStreams();
            Response greeting = readResponse();
            if (greeting.code != 220) {
                throw new SmtpException("SMTP 服务器拒绝连接（" + greeting.code + "）："
                        + firstLine(greeting.text));
            }
        } catch (SmtpException e) {
            throw e;
        } catch (Exception e) {
            closeQuietly();
            throw e;
        }
    }

    private void ehlo() throws Exception {
        writeLine("EHLO smsforward.local");
        Response response = readResponse();
        if (response.code != 250) {
            // A few tiny SMTP servers only speak HELO. AUTH/STARTTLS will then fail with a
            // clear message, but at least the greeting is handled consistently.
            writeLine("HELO smsforward.local");
            response = readResponse();
            if (response.code != 250) {
                throw new SmtpException("SMTP 握手失败（" + response.code + "）："
                        + firstLine(response.text));
            }
        }
    }

    private void startTls() throws Exception {
        writeLine("STARTTLS");
        Response response = readResponse();
        if (response.code != 220) {
            throw new SmtpException("服务器未接受 STARTTLS（" + response.code + "），请改用 SSL/TLS 465 端口");
        }
        SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(socket, config.smtpHost, config.smtpPort, true);
        ssl.setUseClientMode(true);
        ssl.setSoTimeout(READ_TIMEOUT_MS);
        enableHostnameVerification(ssl);
        ssl.startHandshake();
        socket = ssl;
        openStreams();
    }

    private void authenticate() throws Exception {
        String user = config.smtpUser;
        String pass = config.smtpPassword;
        Base64.Encoder encoder = Base64.getEncoder();

        writeLine("AUTH LOGIN");
        Response response = readResponse();
        if (response.code == 334) {
            writeLine(encoder.encodeToString(user.getBytes(StandardCharsets.UTF_8)));
            response = readResponse();
            if (response.code == 334) {
                writeLine(encoder.encodeToString(pass.getBytes(StandardCharsets.UTF_8)));
                response = readResponse();
            }
            if (response.code == 235) return;
            if (response.code == 535 || response.code == 534) {
                throw new SmtpException("邮箱认证失败：请检查邮箱账号和授权码。QQ 邮箱需填写 16 位授权码，不是 QQ 密码");
            }
            throw new SmtpException("邮箱认证失败（" + response.code + "）：" + firstLine(response.text));
        }

        // Some servers only advertise AUTH PLAIN. Try it if LOGIN is not accepted.
        if (response.code == 500 || response.code == 502 || response.code == 504 || response.code == 501) {
            String token = encoder.encodeToString(("\0" + user + "\0" + pass)
                    .getBytes(StandardCharsets.UTF_8));
            writeLine("AUTH PLAIN " + token);
            response = readResponse();
            if (response.code == 235) return;
            if (response.code == 334) {
                writeLine(token);
                response = readResponse();
                if (response.code == 235) return;
            }
            if (response.code == 535 || response.code == 534) {
                throw new SmtpException("邮箱认证失败：请检查邮箱账号和授权码");
            }
            throw new SmtpException("邮箱认证失败（" + response.code + "）：" + firstLine(response.text));
        }

        if (response.code == 535 || response.code == 534) {
            throw new SmtpException("邮箱认证失败：请检查邮箱账号和授权码。QQ 邮箱需填写 16 位授权码，不是 QQ 密码");
        }
        throw new SmtpException("服务器未提供可用的 AUTH 登录方式（" + response.code + "）");
    }

    private void mailFrom() throws Exception {
        String from = config.smtpUser.replace("<", "").replace(">", "")
                .replace("\r", "").replace("\n", "").trim();
        writeLine("MAIL FROM:<" + from + ">");
        Response response = readResponse();
        if (response.code != 250) {
            throw new SmtpException("发件人被服务器拒绝（" + response.code + "）：" + firstLine(response.text));
        }
    }

    private void recipients() throws Exception {
        List<String> recipients = config.recipientList();
        if (recipients.isEmpty()) throw new SmtpException("没有收件邮箱");
        for (String recipient : recipients) {
            String safe = recipient.replace("<", "").replace(">", "")
                    .replace("\r", "").replace("\n", "").trim();
            writeLine("RCPT TO:<" + safe + ">");
            Response response = readResponse();
            if (response.code != 250 && response.code != 251) {
                throw new SmtpException("收件人被服务器拒绝（" + response.code + "）："
                        + firstLine(response.text));
            }
        }
    }

    private void data(String message) throws Exception {
        writeLine("DATA");
        Response response = readResponse();
        if (response.code != 354) {
            throw new SmtpException("服务器拒绝接收邮件正文（" + response.code + "）："
                    + firstLine(response.text));
        }
        String normalized = message.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        for (String line : lines) {
            if (line.startsWith(".")) writer.write(".");
            writer.write(line);
            writer.write("\r\n");
        }
        writer.write(".\r\n");
        writer.flush();
        response = readResponse();
        if (response.code != 250) {
            throw new SmtpException("邮件发送失败（" + response.code + "）：" + firstLine(response.text));
        }
    }

    private void quitQuietly() {
        try {
            writeLine("QUIT");
            readResponse();
        } catch (Exception ignored) {
            // The message has already been accepted by the server.
        }
    }

    private String buildMessage(String subject, String body) {
        String from = config.smtpUser.replace("\r", "").replace("\n", "").trim();
        List<String> recipients = config.recipientList();
        String date = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
                .format(new Date());
        String messageId = UUID.randomUUID().toString() + "@smsforward.local";
        String encodedBody = Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(body.getBytes(StandardCharsets.UTF_8));

        StringBuilder message = new StringBuilder();
        message.append("Date: ").append(date).append("\r\n");
        message.append("From: ").append(encodeWord("短信转发")).append(" <").append(from).append(">\r\n");
        message.append("To: ").append(TextUtils.join(", ", recipients)).append("\r\n");
        message.append("Subject: ").append(encodeWord(cleanHeader(subject))).append("\r\n");
        message.append("Message-ID: <").append(messageId).append(">\r\n");
        message.append("MIME-Version: 1.0\r\n");
        message.append("Content-Type: text/plain; charset=UTF-8\r\n");
        message.append("Content-Transfer-Encoding: base64\r\n");
        message.append("X-Mailer: SmsForwarder/1.0\r\n");
        message.append("\r\n");
        message.append(encodedBody).append("\r\n");
        return message.toString();
    }

    private static String encodeWord(String value) {
        String encoded = Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return "=?UTF-8?B?" + encoded + "?=";
    }

    private static String cleanHeader(String value) {
        if (value == null) return "";
        return value.replace("\r", " ").replace("\n", " ").trim();
    }

    private void openStreams() throws IOException {
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    private void writeLine(String line) throws IOException {
        writer.write(line);
        writer.write("\r\n");
        writer.flush();
    }

    private Response readResponse() throws IOException {
        String first = reader.readLine();
        if (first == null) throw new IOException("服务器断开了连接");
        if (first.length() < 3) throw new IOException("SMTP 返回格式不正确");
        int code;
        try {
            code = Integer.parseInt(first.substring(0, 3));
        } catch (NumberFormatException e) {
            throw new IOException("SMTP 返回格式不正确");
        }
        StringBuilder text = new StringBuilder(first);
        String line = first;
        int guard = 0;
        while (line.length() > 3 && line.charAt(3) == '-' && guard++ < 100) {
            line = reader.readLine();
            if (line == null) break;
            text.append('\n').append(line);
        }
        return new Response(code, text.toString());
    }

    private static void enableHostnameVerification(SSLSocket ssl) {
        SSLParameters parameters = ssl.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        ssl.setSSLParameters(parameters);
    }

    private void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
            // Best effort.
        }
        socket = null;
        reader = null;
        writer = null;
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        int index = text.indexOf('\n');
        String line = index >= 0 ? text.substring(0, index) : text;
        return line.length() > 300 ? line.substring(0, 300) : line;
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private static final class Response {
        final int code;
        final String text;

        Response(int code, String text) {
            this.code = code;
            this.text = text;
        }
    }

    static final class SmtpException extends Exception {
        SmtpException(String message) {
            super(message);
        }

        SmtpException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
