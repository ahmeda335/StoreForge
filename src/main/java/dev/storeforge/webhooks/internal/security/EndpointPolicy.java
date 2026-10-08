package dev.storeforge.webhooks.internal.security;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * SSRF guard. A webhook URL is chosen by the merchant (or by whoever stole a merchant's session),
 * and our servers will POST to it from INSIDE our network. Without this guard it could target
 * the cloud metadata service (169.254.169.254: credentials), the database, or internal admin ports.
 *
 * <p>The rule is about where the request GOES, so it checks every address the host resolves to
 * (not the URL text: {@code http://2130706433/}, {@code http://[::ffff:127.0.0.1]/} and
 * {@code localtest.me} all reach 127.0.0.1). It runs at subscription time for early feedback and,
 * through the HTTP client's DNS resolver, on EVERY delivery with the very addresses the client then
 * connects to: DNS can change between the two (DNS rebinding).
 */
public class EndpointPolicy {

    /** DNS lookup, replaceable in tests. */
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static final List<Cidr> NON_PUBLIC = List.of(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
            "192.0.0.0/24", "192.0.2.0/24", "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24",
            "224.0.0.0/4", "240.0.0.0/4",
            "::/128", "::1/128", "fc00::/7", "fe80::/10", "ff00::/8", "2001:db8::/32", "100::/64"
    ).stream().map(Cidr::parse).toList();
    private static final Cidr NAT64 = Cidr.parse("64:ff9b::/96");
    private static final Cidr SIX_TO_FOUR = Cidr.parse("2002::/16");

    private final boolean requireHttps;
    private final List<Cidr> allowedPrivate;
    private final Resolver resolver;

    public EndpointPolicy(boolean requireHttps, List<Cidr> allowedPrivate, Resolver resolver) {
        this.requireHttps = requireHttps;
        this.allowedPrivate = List.copyOf(allowedPrivate);
        this.resolver = resolver;
    }

    /** @return the parsed URI if it may be used as a webhook destination */
    public URI validate(String url) throws DestinationNotAllowedException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new DestinationNotAllowedException("not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && !requireHttps)) {
            throw new DestinationNotAllowedException(requireHttps ? "must use https" : "must use http or https");
        }
        if (uri.getRawUserInfo() != null) {
            throw new DestinationNotAllowedException("must not contain credentials");
        }
        if (uri.getHost() == null || uri.getRawFragment() != null || url.length() > 2048) {
            throw new DestinationNotAllowedException("must be an absolute URL with a host, no fragment, at most 2048 characters");
        }
        resolveAllowed(uri.getHost());
        return uri;
    }

    /** Resolves the host and returns its addresses only if ALL of them are allowed. */
    public InetAddress[] resolveAllowed(String host) throws DestinationNotAllowedException {
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host.startsWith("[") ? host.substring(1, host.length() - 1) : host);
        } catch (DestinationNotAllowedException e) {
            throw e;
        } catch (UnknownHostException e) {
            throw new DestinationNotAllowedException("host does not resolve");
        }
        if (addresses == null || addresses.length == 0) {
            throw new DestinationNotAllowedException("host does not resolve");
        }
        // ALL, not any: a resolver answering [public, 127.0.0.1] would otherwise get the private one used.
        if (!Arrays.stream(addresses).allMatch(this::allowed)) {
            throw new DestinationNotAllowedException("resolves to a non-public address");
        }
        return addresses;
    }

    private boolean allowed(InetAddress address) {
        return isPublic(address) || allowedPrivate.stream().anyMatch(cidr -> cidr.contains(address));
    }

    public static boolean isPublic(InetAddress address) {
        if (NON_PUBLIC.stream().anyMatch(cidr -> cidr.contains(address))) {
            return false;
        }
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            // IPv6 forms that embed an IPv4 address reach that IPv4 address: check it too.
            if (NAT64.contains(address)) {
                return isPublic(v4(bytes, 12));
            }
            if (SIX_TO_FOUR.contains(address)) {
                return isPublic(v4(bytes, 2));
            }
            boolean compatible = zeros(bytes, 12) || isMapped(bytes);
            if (compatible) {
                return isPublic(v4(bytes, 12));
            }
        }
        return !(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress());
    }

    private static boolean isMapped(byte[] bytes) {
        return zeros(bytes, 10) && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private static boolean zeros(byte[] bytes, int length) {
        for (int i = 0; i < length; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static InetAddress v4(byte[] bytes, int offset) {
        try {
            return InetAddress.getByAddress(Arrays.copyOfRange(bytes, offset, offset + 4));
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

}
