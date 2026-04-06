package com.morix.util;

import java.util.Properties;
import javax.mail.*;
import javax.mail.internet.*;

/**
 * Sends email notifications using JavaMail (SMTP).
 *
 * Configure via environment variables:
 *   MAIL_HOST     - SMTP host (e.g. smtp.gmail.com)
 *   MAIL_PORT     - SMTP port (e.g. 587)
 *   MAIL_USER     - sender email address
 *   MAIL_PASS     - app password / SMTP password
 *   MAIL_FROM     - display name (optional, defaults to MAIL_USER)
 *
 * For Gmail: enable 2FA and create an App Password.
 */
public class MailUtil {

    private static final String HOST = System.getenv("MAIL_HOST");
    private static final String PORT = System.getenv().getOrDefault("MAIL_PORT", "587");
    private static final String USER = System.getenv("MAIL_USER");
    private static final String PASS = System.getenv("MAIL_PASS");
    private static final String FROM = System.getenv().getOrDefault("MAIL_FROM", USER);

    private static boolean isConfigured() {
        return HOST != null && USER != null && PASS != null;
    }

    /**
     * Sends a game invite email to the target player.
     *
     * @param toEmail     recipient email address
     * @param fromPlayer  username of the inviter
     * @param gameLink    URL to join the game (e.g. https://morrix.onrender.com)
     */
    public static void sendGameInvite(String toEmail, String fromPlayer, String gameLink) {
        if (!isConfigured()) {
            System.out.println("[Morix] Mail not configured — skipping invite email to " + toEmail);
            return;
        }

        new Thread(() -> {
            try {
                Properties props = new Properties();
                props.put("mail.smtp.auth", "true");
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.host", HOST);
                props.put("mail.smtp.port", PORT);

                Session session = Session.getInstance(props, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(USER, PASS);
                    }
                });

                Message msg = new MimeMessage(session);
                msg.setFrom(new InternetAddress(USER, "Morrix — 3 Men's Morris"));
                msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
                msg.setSubject("⚔️ " + fromPlayer + " challenged you to a game on Morrix!");

                String html =
                    "<div style='font-family:Arial,sans-serif;max-width:480px;margin:auto;" +
                    "background:#16213e;color:#eef0f8;border-radius:16px;padding:32px;'>" +
                    "<h1 style='color:#e2b96f;letter-spacing:4px;text-align:center;'>MORIX</h1>" +
                    "<p style='font-size:1rem;text-align:center;'>3 Men's Morris · Online</p>" +
                    "<hr style='border-color:#0f3460;margin:20px 0'/>" +
                    "<p style='font-size:1.1rem;'>Hey! <strong style='color:#e2b96f;'>" + fromPlayer +
                    "</strong> has challenged you to a game of <strong>3 Men's Morris</strong>!</p>" +
                    "<p style='color:#b0b8d0;'>Click the button below to join the game and accept the challenge.</p>" +
                    "<div style='text-align:center;margin:28px 0;'>" +
                    "<a href='" + gameLink + "' style='background:#e2b96f;color:#1a1a2e;padding:14px 32px;" +
                    "border-radius:8px;font-weight:700;font-size:1rem;text-decoration:none;'>⚔️ Accept Challenge</a>" +
                    "</div>" +
                    "<p style='color:#555;font-size:0.8rem;text-align:center;'>If you didn't expect this, you can ignore this email.</p>" +
                    "</div>";

                msg.setContent(html, "text/html; charset=utf-8");
                Transport.send(msg);
                System.out.println("[Morix] Invite email sent to " + toEmail + " from " + fromPlayer);

            } catch (Exception e) {
                System.err.println("[Morix] Failed to send invite email: " + e.getMessage());
            }
        }).start();
    }
}
