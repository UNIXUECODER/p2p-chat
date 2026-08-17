package com.p2pchat.network;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Optional;

/**
 * Resolves a wildcard-bind libp2p multiaddr ({@code 0.0.0.0} / {@code ::}) to a concrete address
 * something can actually dial. Promoted out of {@code node-daemon} (see below) once a third
 * caller needed the identical fix — the same "promote once there's a second/third real
 * consumer" threshold this project has applied elsewhere (e.g. {@code core-model} in M3d).
 *
 * <p><b>Background — the M5c gap this replaces.</b> {@code PeerNetworkService.listenAddresses()}
 * can return the address the transport is bound to, not one anything can actually connect to.
 * On a wildcard bind (observed: {@code /ip6/::/tcp/<port>/...} on Windows), that is "listening on
 * every interface" — the wildcard itself. M5c's original fix — a private {@code
 * firstDialableAddress} method, duplicated identically in both {@code ChatListenerMain} and
 * {@code ChatSenderMain} — hardcoded that wildcard to {@code 127.0.0.1}. That was flagged
 * explicitly, in both classes' own Javadoc, as a same-machine-testing fix, not a general one:
 * correct only because M5c's demo runs both processes on one machine. {@code FileSenderMain}
 * (M4c) never got even that fix — it reads {@code network.listenAddresses()[0]} raw — which
 * happened not to matter for that milestone's own real-hardware run, but is the same latent gap.
 *
 * <p><b>What this class actually adds:</b> real local-network-interface enumeration
 * ({@link NetworkInterface}), so a wildcard bind resolves to this machine's actual LAN IP (e.g.
 * {@code 192.168.1.42}) instead of always loopback. This is exactly what M1's own README already
 * told a human to do by hand for two machines on a LAN ("swap 0.0.0.0 for 127.0.0.1... use the
 * listener machine's actual LAN IP") — this class automates that swap, nothing more.
 *
 * <p><b>What this class deliberately does NOT solve</b> — flagged here so it isn't mistaken for
 * more than it is, matching this project's own established practice of being explicit about
 * milestone scope boundaries (see {@code docs/architecture-spec.md §10}'s own "self-address
 * discovery is still an open gap" note, which this class narrows but does not close):
 * <ul>
 *   <li><b>NAT / different networks.</b> A LAN IP is not reachable from outside that LAN. Two
 *   peers on different networks still need the existing direct-first/relay-fallback path
 *   ({@link ConnectionStrategy}, M3a/M3b) actually wired into a caller that currently bypasses
 *   it — this class only improves <i>what address gets reported</i>, it does not add a fallback
 *   path itself. Tracked as M6 work, not attempted here.</li>
 *   <li><b>Multiple candidate interfaces.</b> A machine with several active interfaces (e.g.
 *   Wi-Fi plus a VPN adapter) may have more than one plausible LAN address; {@link #resolve}
 *   picks the first non-loopback, non-virtual, site-local IPv4 address the JDK enumerates —
 *   not necessarily the "right" one for a given topology. A real fix would make this
 *   configurable or attempt each candidate in turn.</li>
 *   <li><b>No public/external IP discovery</b> (STUN-style or otherwise) — still fully unbuilt,
 *   as {@code docs/architecture-spec.md §10} already notes. Out of scope here.</li>
 * </ul>
 */
public final class DialableAddressResolver {

    private DialableAddressResolver() {
    }

    /**
     * Picks the best candidate out of {@code listenAddresses} and, if it is a wildcard bind,
     * resolves it to a real local address. Prefers an already-concrete (non-wildcard) IPv4
     * address if one is present in {@code listenAddresses}; only falls back to interface
     * enumeration when every candidate is a wildcard. Falls back to {@code 127.0.0.1} (M5c's
     * original behavior) if no non-loopback interface can be found — e.g. a fully offline
     * machine — so this never returns an unusable address, only a possibly-too-narrow one.
     *
     * @return a dialable multiaddr, or {@code ""} if {@code listenAddresses} is null/empty
     */
    public static String resolve(String[] listenAddresses) {
        if (listenAddresses == null || listenAddresses.length == 0) {
            return "";
        }

        for (String addr : listenAddresses) {
            if (addr.startsWith("/ip4/") && !addr.startsWith("/ip4/0.0.0.0/")) {
                return addr; // already concrete — nothing to resolve
            }
        }

        String wildcard = null;
        for (String addr : listenAddresses) {
            if (addr.startsWith("/ip4/0.0.0.0/") || addr.startsWith("/ip6/::/")) {
                wildcard = addr;
                break;
            }
        }
        if (wildcard == null) {
            return listenAddresses[0]; // nothing recognized as a wildcard — return as-is
        }

        String lanIp = firstSiteLocalIPv4().orElse("127.0.0.1");
        if (wildcard.startsWith("/ip4/0.0.0.0/")) {
            return wildcard.replaceFirst("^/ip4/0\\.0\\.0\\.0/", "/ip4/" + lanIp + "/");
        }
        return wildcard.replaceFirst("^/ip6/::/", "/ip4/" + lanIp + "/");
    }

    /**
     * Enumerates real network interfaces, returning the first up, non-loopback, non-virtual
     * interface's first site-local (RFC 1918 private-range) IPv4 address. {@code
     * InetAddress.isSiteLocalAddress()} is exactly "is this a 10.x/172.16-31.x/192.168.x
     * address" — deliberately excludes link-local (169.254.x.x, an interface with no real
     * network) and, by construction, loopback and public addresses (a machine directly bound to
     * a public IP is not a scenario this same-LAN-scoped fix targets).
     */
    private static Optional<String> firstSiteLocalIPv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress candidate = addresses.nextElement();
                    if (candidate instanceof Inet4Address && candidate.isSiteLocalAddress()) {
                        return Optional.of(candidate.getHostAddress());
                    }
                }
            }
        } catch (SocketException e) {
            // Falls through to the loopback default in resolve() — address resolution failing
            // should never take down a caller whose actual job is sending a chat message or a
            // file offer; this is a best-effort improvement over the previous hardcoded default,
            // not a step that is allowed to become a new point of failure.
        }
        return Optional.empty();
    }
}
