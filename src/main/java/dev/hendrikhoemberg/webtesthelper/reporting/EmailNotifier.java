package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.SmtpSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.TlsMode;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

@Component
public class EmailNotifier implements Notifier {

    private final AppSettings appSettings;

    public EmailNotifier(AppSettings appSettings) {
        this.appSettings = appSettings;
    }

    @Override
    public void deliver(OutboundMail mail) throws MailDeliveryException {
        SmtpSettings smtp = appSettings.smtp();
        if (smtp == null || !smtp.configured()) {
            throw new MailDeliveryException("Der SMTP-Server ist nicht konfiguriert.");
        }

        JavaMailSenderImpl sender = buildSender(smtp);
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(smtp.fromAddress());
            helper.setTo(mail.recipient());
            helper.setSubject(mail.subject());
            helper.setText(mail.text(), mail.html());
            sender.send(message);
        } catch (Exception e) {
            String msg = (e.getMessage() != null && !e.getMessage().isBlank()) ? e.getMessage() : e.toString();
            throw new MailDeliveryException(msg, e);
        }
    }

    private JavaMailSenderImpl buildSender(SmtpSettings smtp) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtp.host());
        sender.setPort(smtp.port());
        if (smtp.username() != null && !smtp.username().isBlank()) {
            sender.setUsername(smtp.username());
        }
        if (smtp.password() != null && !smtp.password().isEmpty()) {
            sender.setPassword(smtp.password());
        }
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        if (smtp.username() != null && !smtp.username().isBlank()) {
            props.put("mail.smtp.auth", "true");
        } else {
            props.put("mail.smtp.auth", "false");
        }
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "5000");
        props.put("mail.smtp.writetimeout", "5000");

        TlsMode tls = smtp.tls() != null ? smtp.tls() : TlsMode.STARTTLS;
        switch (tls) {
            case STARTTLS -> {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.ssl.enable", "false");
            }
            case SSL -> {
                props.put("mail.smtp.starttls.enable", "false");
                props.put("mail.smtp.ssl.enable", "true");
            }
            case NONE -> {
                props.put("mail.smtp.starttls.enable", "false");
                props.put("mail.smtp.ssl.enable", "false");
            }
        }
        return sender;
    }
}
