package dev.storeforge.notifications;

/** A plain-text email. {@code toString()} omits the body: bodies may contain single-use links. */
public record EmailMessage(String to, String subject, String body) {

    @Override
    public String toString() {
        return "EmailMessage[subject=" + subject + "]";
    }
}
