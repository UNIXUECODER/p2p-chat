package com.p2pchat.network;

import com.p2pchat.model.PeerId;
import io.libp2p.core.Connection;
import io.libp2p.core.Host;
import io.libp2p.core.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TransportFramingAndStreamLeakStressTest {

    private Libp2pNetworkService senderService;
    private Libp2pNetworkService receiverService;

    @AfterEach
    void tearDown() throws Exception {
        if (senderService != null) {
            senderService.stop();
        }
        if (receiverService != null) {
            receiverService.stop();
        }
    }

    private Host extractHost(Libp2pNetworkService service) throws Exception {
        Field hostField = Libp2pNetworkService.class.getDeclaredField("host");
        hostField.setAccessible(true);
        return (Host) hostField.get(service);
    }

    @Test
    @Disabled("Pending Step 3.3 protocol 0.2.0 length-prefixed framing (ADR-0002)")
    void testLargePayloadFramingSlicingOverMplex() throws Exception {
        int receiverPort = 19310;
        int senderPort = 19311;

        byte[] receiverSeed = new byte[32];
        receiverSeed[0] = 10;
        byte[] senderSeed = new byte[32];
        senderSeed[0] = 11;

        List<byte[]> receivedChunks = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch receivedLatch = new CountDownLatch(1);

        receiverService = new Libp2pNetworkService();
        receiverService.start(receiverPort, receiverSeed, (sender, data) -> {
            receivedChunks.add(data);
            receivedLatch.countDown();
        });

        String receiverAddress = DialableAddressResolver.resolve(receiverService.listenAddresses());
        System.out.println("Resolved receiver address: " + receiverAddress);

        senderService = new Libp2pNetworkService();
        senderService.start(senderPort, senderSeed, (sender, data) -> {});

        // Create a 2 MiB payload (larger than 1 MiB mplex frame limit)
        int payloadSize = 2 * 1024 * 1024; // 2 MiB
        byte[] largePayload = new byte[payloadSize];
        Arrays.fill(largePayload, (byte) 0x42);

        System.out.println("Sending 2 MiB payload from sender to " + receiverAddress + " ...");
        senderService.sendEnvelope(receiverAddress, largePayload, 10000);

        // Wait to see what arrives at receiver
        boolean arrived = receivedLatch.await(5, TimeUnit.SECONDS);
        // Wait an extra second in case multiple chunks arrive separately
        Thread.sleep(1000);

        System.out.println("Received message arrival: " + arrived);
        System.out.println("Total onMessage callbacks triggered on receiver: " + receivedChunks.size());

        int totalBytesReceived = 0;
        for (int i = 0; i < receivedChunks.size(); i++) {
            byte[] chunk = receivedChunks.get(i);
            totalBytesReceived += chunk.length;
            System.out.println("  Chunk " + i + " length: " + chunk.length + " bytes (" + (chunk.length / 1024) + " KiB)");
        }
        System.out.println("Total bytes received: " + totalBytesReceived + " / " + payloadSize);

        assertThat(receivedChunks).hasSize(1);
        assertThat(receivedChunks.get(0)).isEqualTo(largePayload);
    }

    @Test
    @Disabled("Pending Step 3.3 protocol 0.2.0 stream close at both sender and receiver (ADR-0002)")
    void testDirectSendEnvelopeLeavesStreamsOpenLeakingResources() throws Exception {
        int receiverPort = 19320;
        int senderPort = 19321;

        byte[] receiverSeed = new byte[32];
        receiverSeed[0] = 20;
        byte[] senderSeed = new byte[32];
        senderSeed[0] = 21;

        AtomicInteger receiveCount = new AtomicInteger(0);
        receiverService = new Libp2pNetworkService();
        receiverService.start(receiverPort, receiverSeed, (sender, data) -> receiveCount.incrementAndGet());

        String receiverAddress = DialableAddressResolver.resolve(receiverService.listenAddresses());
        System.out.println("Resolved receiver address: " + receiverAddress);

        senderService = new Libp2pNetworkService();
        senderService.start(senderPort, senderSeed, (sender, data) -> {});

        // Send 20 separate envelopes sequentially
        int sendCount = 20;
        System.out.println("Sending " + sendCount + " envelopes sequentially from sender to " + receiverAddress + " ...");
        for (int i = 0; i < sendCount; i++) {
            senderService.sendEnvelope(receiverAddress, ("msg " + i).getBytes(), 5000);
        }

        Thread.sleep(1000);
        System.out.println("Receiver saw messages: " + receiveCount.get() + " / " + sendCount);

        // Inspect connection on sender
        Host senderHost = extractHost(senderService);
        List<Connection> connections = senderHost.getNetwork().getConnections();
        System.out.println("Sender active connections count: " + connections.size());

        for (Connection conn : connections) {
            // jvm-libp2p Connection interface has conn.getStreams() or conn.muxerSession()
            System.out.println("Connection: " + conn);
            var muxerSession = conn.muxerSession();
            System.out.println("MuxerSession class: " + muxerSession.getClass().getName());
            for (java.lang.reflect.Method m : muxerSession.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && (m.getName().toLowerCase().contains("stream") || m.getName().toLowerCase().contains("channel") || m.getName().toLowerCase().contains("count"))) {
                    try {
                        Object res = m.invoke(muxerSession);
                        System.out.println("  muxerSession." + m.getName() + "() -> " + res);
                    } catch (Exception e) {
                        System.out.println("  muxerSession." + m.getName() + "() -> err: " + e.getMessage());
                    }
                }
            }
            Class<?> cur = muxerSession.getClass();
            int senderOpenStreams = -1;
            while (cur != null && cur != Object.class) {
                for (Field f : cur.getDeclaredFields()) {
                    if (f.getName().equals("streamMap")) {
                        f.setAccessible(true);
                        var map = (java.util.Map<?, ?>) f.get(muxerSession);
                        senderOpenStreams = map.size();
                        System.err.println("===> Sender streamMap size: " + senderOpenStreams);
                    }
                }
                cur = cur.getSuperclass();
            }
            assertThat(senderOpenStreams).isZero();
        }

        // Inspect connection on receiver
        Host receiverHost = extractHost(receiverService);
        List<Connection> receiverConns = receiverHost.getNetwork().getConnections();
        System.out.println("Receiver active connections count: " + receiverConns.size());
        for (Connection conn : receiverConns) {
            var muxerSession = conn.muxerSession();
            Class<?> cur = muxerSession.getClass();
            int receiverOpenStreams = -1;
            while (cur != null && cur != Object.class) {
                for (Field f : cur.getDeclaredFields()) {
                    if (f.getName().equals("streamMap")) {
                        f.setAccessible(true);
                        var map = (java.util.Map<?, ?>) f.get(muxerSession);
                        receiverOpenStreams = map.size();
                        System.err.println("===> Receiver streamMap size: " + receiverOpenStreams);
                    }
                }
                cur = cur.getSuperclass();
            }
            assertThat(receiverOpenStreams).isZero();
        }
    }
}
