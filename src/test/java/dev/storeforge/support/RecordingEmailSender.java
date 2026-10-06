package dev.storeforge.support;

import dev.storeforge.notifications.EmailMessage;
import dev.storeforge.notifications.EmailSender;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Test double for the email port: keeps sent messages in memory so tests can read links from them. */
public class RecordingEmailSender implements EmailSender {

    private static final Pattern TOKEN = Pattern.compile("[#&]token=([A-Za-z0-9_-]+)");

    private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(EmailMessage message) {
        sent.add(message);
    }

    public List<EmailMessage> sentTo(String address) {
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
