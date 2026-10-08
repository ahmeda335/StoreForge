package dev.storeforge.webhooks.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.storeforge.webhooks.internal.security.Cidr;
import dev.storeforge.webhooks.internal.security.DestinationNotAllowedException;
import dev.storeforge.webhooks.internal.security.EndpointPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The SSRF guard decides on RESOLVED ADDRESSES; every bypass trick below must end at the same check. */
class EndpointPolicyTest {

    /** A fake DNS: names map to fixed answers; IP literals resolve to themselves (no network). */
    static EndpointPolicy.Resolver dns(Map<String, List<String>> records) {
        return host -> {
            List<String> answer = records.get(host);
            if (answer == null) {
                return InetAddress.getAllByName(host.matches("[0-9a-fA-F.:]+") ? host : "invalid.invalid.");
            }
            InetAddress[] addresses = new InetAddress[answer.size()];
            for (int i = 0; i < addresses.length; i++) {
                addresses[i] = InetAddress.getByName(answer.get(i));
            }
            return addresses;
        };
    }

    static final EndpointPolicy STRICT = new EndpointPolicy(true, List.of(), dns(Map.of(
            "hooks.example.com", List.of("93.184.216.34"),
            "internal.example.com", List.of("10.1.2.3"),
            "mixed.example.com", List.of("93.184.216.34", "127.0.0.1"),
            "metadata.example.com", List.of("169.254.169.254"),
            "v6.example.com", List.of("2606:2800:220:1:248:1893:25c8:1946"))));

    @Test
    void acceptsPublicHttpsDestinations() {
        assertThatCode(() -> STRICT.validate("https://hooks.example.com/storeforge?x=1")).doesNotThrowAnyException();
        assertThatCode(() -> STRICT.validate("https://v6.example.com:8443/hook")).doesNotThrowAnyException();
        assertThatCode(() -> STRICT.validate("https://93.184.216.34/hook")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/", "https://localhost/", "https://10.0.0.5/", "https://172.16.3.4/", "https://192.168.1.1/",
            "https://169.254.169.254/latest/meta-data/",   // cloud metadata (credentials!)
            "https://100.64.0.1/",                         // carrier-grade NAT
            "https://0.0.0.0/", "https://224.0.0.1/",
            "https://[::1]/", "https://[fd00::1]/", "https://[fe80::1]/",
            "https://[::ffff:127.0.0.1]/",                 // IPv4-mapped IPv6
            "https://[64:ff9b::a9fe:a9fe]/",               // NAT64 form of 169.254.169.254
            "https://[2002:a00:1::]/",                     // 6to4 form of 10.0.0.1
            "https://2130706433/",                         // 127.0.0.1 as a decimal number
            "https://internal.example.com/", "https://metadata.example.com/",
            "https://mixed.example.com/"                   // one bad answer is enough to refuse
    })
    void refusesEveryRouteToNonPublicAddresses(String url) {
        assertThatThrownBy(() -> STRICT.validate(url)).isInstanceOf(DestinationNotAllowedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://hooks.example.com/", "ftp://hooks.example.com/", "https://user:pass@hooks.example.com/",
            "https://hooks.example.com/#frag", "/relative", "https://does-not-exist.example.com/", "not a url"})
    void refusesMalformedOrUnsafeUrls(String url) {
        assertThatThrownBy(() -> STRICT.validate(url)).isInstanceOf(DestinationNotAllowedException.class);
    }

    /** The check at delivery time sees a DNS change made after the subscription was accepted. */
    @Test
    void dnsRebindingIsCaughtAtResolveTime() throws UnknownHostException {
        Map<String, List<String>> records = new java.util.HashMap<>(Map.of("rebind.example.com", List.of("93.184.216.34")));
        EndpointPolicy policy = new EndpointPolicy(true, List.of(), host -> dns(records).resolve(host));
        assertThatCode(() -> policy.validate("https://rebind.example.com/")).doesNotThrowAnyException();

        records.put("rebind.example.com", List.of("169.254.169.254"));

        assertThatThrownBy(() -> policy.resolveAllowed("rebind.example.com")).isInstanceOf(DestinationNotAllowedException.class);
    }

    @Test
    void localDevelopmentEscapeHatchIsExplicitAndNarrow() {
        EndpointPolicy local = new EndpointPolicy(false, List.of(Cidr.parse("127.0.0.0/8")), dns(Map.of()));
        assertThatCode(() -> local.validate("http://127.0.0.1:9000/hook")).doesNotThrowAnyException();
        assertThatThrownBy(() -> local.validate("http://169.254.169.254/")).isInstanceOf(DestinationNotAllowedException.class);
        assertThat(Cidr.parse("10.0.0.0/8").contains(addr("10.255.0.1"))).isTrue();
        assertThat(Cidr.parse("10.0.0.0/8").contains(addr("11.0.0.1"))).isFalse();
        assertThat(Cidr.parse("fc00::/7").contains(addr("fd12::1"))).isTrue();
    }

    private static InetAddress addr(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
