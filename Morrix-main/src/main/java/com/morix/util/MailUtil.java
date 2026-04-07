package com.morix.util;

import javax.mail.*;
import javax.mail.internet.*;
import java.util.Properties;

/**
 * Sends transactional emails for Morix.
 *
 * Required environment variables:
 *   MAIL_HOST     — SMTP host,       e.g. smtp.gmail.com
 *   MAIL_PORT     — SMTP port,       e.g. 587
 *   MAIL_USER     — SMTP username / sender address
 *   MAIL_PASSWORD — SMTP password or app-password
 *   MAIL_FROM     — "From" display,  e.g. "Morix Game <no-reply@yourdomain.com>"
 *                   (defaults to MAIL_USER if not set)
 */
public class MailUtil {

    private static final String HOST     = System.getenv("MAIL_HOST");
    private static final String PORT     = System.getenv("MAIL_PORT") != null
                                               ? System.getenv("MAIL_PORT") : "587";
    private static final String USER     = System.getenv("MAIL_USER");
    private static final String PASSWORD = System.getenv("MAIL_PASSWORD");
    private static final String FROM     = System.getenv("MAIL_FROM") != null
                                               ? System.getenv("MAIL_FROM") : USER;

    /**
     * Returns true if all required environment variables are set.
     */
    public static boolean isConfigured() {
        return HOST != null && !HOST.isEmpty()
            && USER != null && !USER.isEmpty()
            && PASSWORD != null && !PASSWORD.isEmpty();
    }

    /**
     * Sends a game-invite email to {@code toEmail}.
     *
     * @param toEmail   recipient's email address
     * @param fromUser  Morix username of the person who sent the invite
     * @param gameLink  public URL of the game, e.g. "https://morix.onrender.com"
     */
    public static void sendGameInvite(String toEmail, String fromUser, String gameLink)
            throws MessagingException {

        if (!isConfigured()) {
            throw new MessagingException(
                "Mail not configured — set MAIL_HOST, MAIL_USER, MAIL_PASSWORD env vars.");
        }

        Properties props = new Properties();
        props.put("mail.smtp.auth",            "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host",            HOST);
        props.put("mail.smtp.port",            PORT);
        props.put("mail.smtp.ssl.trust",       HOST);

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(USER, PASSWORD);
            }
        });

        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(FROM));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
        message.setSubject(fromUser + " challenged you to a game on Morix!");

        String html =
            "<!DOCTYPE html><html><body style='font-family:Arial,sans-serif;background:#0f0f1a;color:#eef0f8;padding:32px'>" +
            "<div style='max-width:480px;margin:0 auto;background:#16213e;border:1px solid #0f3460;border-radius:16px;padding:32px;text-align:center'>" +
            "<h1 style='color:#e2b96f;letter-spacing:4px;font-size:2rem'>MORIX</h1>" +
            "<p style='color:#b0b8d0;margin:8px 0 0'>3 Men's Morris · Online</p>" +
            "<hr style='border:none;border-top:1px solid #0f3460;margin:24px 0'/>" +
            "<p style='font-size:1.1rem'>⚔️ <strong style='color:#e2b96f'>" + escHtml(fromUser) + "</strong> wants to play!</p>" +
            "<p style='color:#7a8099;font-size:0.9rem;margin:8px 0 24px'>You have an open game invite waiting for you.</p>" +
            "<a href='" + escHtml(gameLink) + "' " +
            "style='display:inline-block;background:#e2b96f;color:#1a1a2e;padding:12px 32px;border-radius:8px;font-weight:700;font-size:1rem;text-decoration:none'>▶ Play Now</a>" +
            "<p style='color:#333;font-size:0.72rem;margin-top:28px'>You received this because someone added you as a friend on Morix.</p>" +
            "</div></body></html>";

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(html, "text/html; charset=UTF-8");

        Multipart multipart = new MimeMultipart("alternative");
        // Plain-text fallback
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(fromUser + " challenged you to Morix! Play at: " + gameLink, "UTF-8");
        multipart.addBodyPart(textPart);
        multipart.addBodyPart(htmlPart);

        message.setContent(multipart);
        Transport.send(message);
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
                .replace("\"","&quot;").replace("'","&#39;");
    }
}
