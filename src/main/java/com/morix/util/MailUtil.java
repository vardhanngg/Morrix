package com.morix.util;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Sends transactional emails for Morix via the Resend HTTP API.
 * No SMTP — pure HTTPS on port 443, works on all hosting platforms.
 *
 * Required environment variable:
 *   RESEND_API_KEY  — your Resend API key, e.g. re_xxxxxxxxxxxx
 *
 * Optional:
 *   MAIL_FROM       — sender address shown to recipient
 *                     Must be a verified domain in Resend, OR use
 *                     the free default: "onboarding@resend.dev"
 *                     (only delivers to the account owner's email on free plan)
 */
public class MailUtil {

    private static final String API_KEY   = System.getenv("RESEND_API_KEY");
    private static final String MAIL_FROM =
            System.getenv("MAIL_FROM") != null && !System.getenv("MAIL_FROM").isEmpty()
            ? System.getenv("MAIL_FROM")
            : "Morix Game <onboarding@resend.dev>";

    public static boolean isConfigured() {
        return API_KEY != null && !API_KEY.isEmpty();
    }

    public static void sendGameInvite(String toEmail, String fromUser, String gameLink)
            throws Exception {

        if (!isConfigured()) {
            throw new Exception("Mail not configured — set RESEND_API_KEY env var.");
        }

        String html =
            "<!DOCTYPE html><html><body style='font-family:Arial,sans-serif;background:#0f0f1a;color:#eef0f8;padding:32px'>" +
            "<div style='max-width:480px;margin:0 auto;background:#16213e;border:1px solid #0f3460;border-radius:16px;padding:32px;text-align:center'>" +
            "<h1 style='color:#e2b96f;letter-spacing:4px;font-size:2rem'>MORIX</h1>" +
            "<p style='color:#b0b8d0;margin:8px 0 0'>3 Men&#39;s Morris &middot; Online</p>" +
            "<hr style='border:none;border-top:1px solid #0f3460;margin:24px 0'/>" +
            "<p style='font-size:1.1rem'>&#x2694;&#xFE0F; <strong style='color:#e2b96f'>" + escHtml(fromUser) + "</strong> wants to play!</p>" +
            "<p style='color:#7a8099;font-size:0.9rem;margin:8px 0 24px'>You have an open game invite waiting for you.</p>" +
            "<a href='" + escHtml(gameLink) + "' " +
            "style='display:inline-block;background:#e2b96f;color:#1a1a2e;padding:12px 32px;border-radius:8px;font-weight:700;font-size:1rem;text-decoration:none'>&#x25B6; Play Now</a>" +
            "<p style='color:#333;font-size:0.72rem;margin-top:28px'>You received this because someone added you as a friend on Morix.</p>" +
            "</div></body></html>";

        String subject = escJson(fromUser) + " challenged you to a game on Morix!";
        String text    = fromUser + " challenged you to Morix! Play at: " + gameLink;

        String json = "{"
            + "\"from\":\""    + escJson(MAIL_FROM) + "\","
            + "\"to\":[\""     + escJson(toEmail)   + "\"],"
            + "\"subject\":\"" + escJson(subject)   + "\","
            + "\"html\":\""    + escJson(html)      + "\","
            + "\"text\":\""    + escJson(text)      + "\""
            + "}";

        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection)
                new URL("https://api.resend.com/emails").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + API_KEY);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Content-Length", String.valueOf(body.length));
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }

        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            String resp = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new Exception("Resend API error " + status + ": " + resp);
        }
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
                .replace("\"","&quot;").replace("'","&#39;");
    }

    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\")
                .replace("\"","\\\"")
                .replace("\n","\\n")
                .replace("\r","\\r")
                .replace("\t","\\t");
    }
}
