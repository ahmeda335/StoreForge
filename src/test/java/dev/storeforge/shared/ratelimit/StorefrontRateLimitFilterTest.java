package dev.storeforge.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StorefrontRateLimitFilterTest {

    @Test
    void ipv4AddressesAreTheirOwnKey() {
        assertThat(StorefrontRateLimitFilter.clientKey("203.0.113.7")).isEqualTo("203.0.113.7");
    }

    /** One host usually controls a whole /64; rotating within it must not give fresh budgets. */
    @Test
    void ipv6AddressesAreGroupedByTheirSlash64() {
        String a = StorefrontRateLimitFilter.clientKey("2001:db8:1:2:aaaa::1");
        String b = StorefrontRateLimitFilter.clientKey("2001:db8:1:2:ffff:ffff:ffff:fffe");
        String other = StorefrontRateLimitFilter.clientKey("2001:db8:1:3::1");
        assertThat(a).isEqualTo(b).endsWith("/64");
        assertThat(other).isNotEqualTo(a);
    }
}
