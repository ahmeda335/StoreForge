package dev.storeforge.identity;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.AuthFlows;
import dev.storeforge.support.AuthFlows.Session;
import dev.storeforge.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Runs the credential-heavy flows (including failure paths that log warnings) and asserts that no
 * secret appears anywhere in the application's output. Grows with every module that handles secrets.
 */
@ExtendWith(OutputCaptureExtension.class)
class SensitiveDataLoggingIT extends IntegrationTest {

    @Test
    void credentialsAndTokensNeverAppearInLogs(CapturedOutput output) {
        AuthFlows auth = new AuthFlows(api, mailbox);
        String email = uniqueEmail();
        Session session = auth.newVerifiedSession(email);
        Session rotated = AuthFlows.sessionFrom(auth.refresh(session.refreshToken()));
        auth.refresh(session.refreshToken());                 // reuse detection → WARN log
        auth.login(email, "a wrong password attempt");          // failed login
        String verificationToken = mailbox.latestToken(email, "Verify").orElseThrow();

        assertThat(output.getAll())
                .doesNotContain(AuthFlows.PASSWORD)
                .doesNotContain("a wrong password attempt")
                .doesNotContain(session.accessToken())
                .doesNotContain(session.refreshToken())
                .doesNotContain(rotated.refreshToken())
                .doesNotContain(verificationToken)
                .contains("Refresh token reuse detected");   // the warning itself IS logged (with ids only)
    }
}
