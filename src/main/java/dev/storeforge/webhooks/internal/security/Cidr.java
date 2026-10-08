package dev.storeforge.webhooks.internal.security;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** An address range like {@code 10.0.0.0/8} or {@code fc00::/7}. */
public record Cidr(byte[] network, int prefix) {

    public static Cidr parse(String cidr) {
        String[] parts = cidr.trim().split("/");
        try {
            byte[] address = InetAddress.getByName(parts[0]).getAddress();
            int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : address.length * 8;
            if (prefix < 0 || prefix > address.length * 8) {
                throw new IllegalArgumentException("Invalid prefix in " + cidr);
            }
            return new Cidr(address, prefix);
        } catch (UnknownHostException | NumberFormatException e) {
            throw new IllegalArgumentException("Invalid CIDR " + cidr, e);
        }
    }

    public boolean contains(InetAddress address) {
        byte[] candidate = address.getAddress();
        if (candidate.length != network.length) {
            return false;
        }
        int bits = network.length * 8;
        BigInteger mask = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
                .shiftRight(prefix).xor(BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE));
        return new BigInteger(1, candidate).and(mask).equals(new BigInteger(1, network).and(mask));
    }
}
