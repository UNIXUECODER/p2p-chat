package com.p2pchat.network;

import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verified by actually compiling and running this directly (see the M5d section of README.md) —
 * {@code core-network} depends on {@code jvm-libp2p} elsewhere, but this class itself is pure
 * JDK, so unlike most of {@code core-network} it could be isolated and run in a sandbox without
 * network access to Cloudsmith/JitPack, the same reasoning M4a/M5a used for their own
 * zero-dependency modules.
 */
class DialableAddressResolverTest {

    @Test
    void concreteIPv4AddressIsReturnedUnchanged() {
        String result = DialableAddressResolver.resolve(new String[]{"/ip4/10.0.0.5/tcp/9000/p2p/ABC"});
        assertThat(result).isEqualTo("/ip4/10.0.0.5/tcp/9000/p2p/ABC");
    }

    @Test
    void wildcardIPv4ResolvesToRealLanIpOrLoopbackFallback() {
        String result = DialableAddressResolver.resolve(new String[]{"/ip4/0.0.0.0/tcp/9000/p2p/XYZ"});
        String expectedIp = realLanIPv4().orElse("127.0.0.1");
        assertThat(result).isEqualTo("/ip4/" + expectedIp + "/tcp/9000/p2p/XYZ");
    }

    @Test
    void wildcardIPv6ResolvesTheSameWayAsWildcardIPv4() {
        // The exact case M5c hit on real hardware: network.listenAddresses()[0] returning
        // "/ip6/::/tcp/9200/p2p/12D3KooWJ4Fr..." rather than a dialable address.
        String result = DialableAddressResolver.resolve(new String[]{"/ip6/::/tcp/9200/p2p/12D3KooWJ4Fr"});
        String expectedIp = realLanIPv4().orElse("127.0.0.1");
        assertThat(result).isEqualTo("/ip4/" + expectedIp + "/tcp/9200/p2p/12D3KooWJ4Fr");
    }

    @Test
    void concreteAddressInArrayIsPreferredOverWildcardEntry() {
        String result = DialableAddressResolver.resolve(new String[]{
                "/ip6/::/tcp/9000/p2p/ABC",
                "/ip4/192.168.1.50/tcp/9000/p2p/ABC"
        });
        assertThat(result).isEqualTo("/ip4/192.168.1.50/tcp/9000/p2p/ABC");
    }

    @Test
    void nullOrEmptyInputReturnsEmptyString() {
        assertThat(DialableAddressResolver.resolve(null)).isEqualTo("");
        assertThat(DialableAddressResolver.resolve(new String[0])).isEqualTo("");
    }

    @Test
    void unrecognizedAddressSchemeIsReturnedAsIs() {
        String result = DialableAddressResolver.resolve(new String[]{"/dns4/example.com/tcp/9000/p2p/ABC"});
        assertThat(result).isEqualTo("/dns4/example.com/tcp/9000/p2p/ABC");
    }

    /** Mirrors DialableAddressResolver's own private interface-enumeration logic, for the test's own expected-value computation. */
    private static Optional<String> realLanIPv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) {
                    continue;
                }
                Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress candidate = addresses.nextElement();
                    if (candidate instanceof Inet4Address && candidate.isSiteLocalAddress()) {
                        return Optional.of(candidate.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
            // matches DialableAddressResolver's own fall-through-to-empty behavior
        }
        return Optional.empty();
    }
}
