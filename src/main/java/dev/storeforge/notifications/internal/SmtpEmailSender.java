package dev.storeforge.notifications.internal;

import dev.storeforge.notifications.EmailDeliveryException;
import dev.storeforge.notifications.EmailMessage;
import dev.storeforge.notifications.EmailSender;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final MeterRegistry meterRegistry;
    private final String from;

    SmtpEmailSender(JavaMailSender mailSender, MeterRegistry meterRegistry,
                    @Value("${storeforge.mail.from}") String from) {
        this.mailSender = mailSender;
        this.meterRegistry = meterRegistry;
        this.from = from;
    }

    @Override
    public void send(EmailMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.body());
        try {
            mailSender.send(mail);
            meterRegistry.counter("storeforge.notifications.emails", "outcome", "sent").increment();
        } catch (MailException e) {
            meterRegistry.counter("storeforge.notifications.emails", "outcome", "failed").increment();
            throw new EmailDeliveryException("SMTP delivery failed", e);
        }
    }
}
