package dev.storeforge.support;

import dev.storeforge.notifications.EmailDeliveryException;
import dev.storeforge.notifications.EmailMessage;
import dev.storeforge.notifications.EmailSender;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test double for the email port: keeps sent messages in memory so tests can read links from them.
 *
 * <p>Since P9, emails are queued in the database and SENT BY THE JOB WORKER. Reading the mailbox
 * first drains the job queue (scheduling is off in tests), so "what did the app send?" includes
 * everything committed so far. {@link #failNext} simulates an SMTP outage.
 */
public class RecordingEmailSender implements EmailSender {

    private static final Pattern TOKEN = Pattern.compile("[#&]token=([A-Za-z0-9_-]+)");

    private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresToSimulate = new AtomicInteger();
    private final Runnable drainJobs;

    public RecordingEmailSender(Runnable drainJobs) {
        this.drainJobs = drainJobs;
    }

    @Override
    public void send(EmailMessage message) {
        if (failuresToSimulate.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new EmailDeliveryException("simulated SMTP outage", new IllegalStateException("connection refused"));
        }
        sent.add(message);
    }

    public void failNext(int times) {
        failuresToSimulate.set(times);
    }

    public List<EmailMessage> sentTo(String address) {
        drainJobs.run();
        return sent.stream().filter(message -> message.to().equalsIgnoreCase(address)).toList();
    }

    /** The token from the most recent email to {@code address} whose subject contains {@code subjectPart}. */
    public Optional<String> latestToken(String address, String subjectPart) {
        List<EmailMessage> matching = sentTo(address).stream()
                .filter(message -> message.subject().contains(subjectPart))
                .toList();
        if (matching.isEmpty()) {
            return Optional.empty();
        }
        Matcher matcher = TOKEN.matcher(matching.getLast().body());
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
