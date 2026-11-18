package com.oneshot.surround;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class JamWireTest {
    @Test public void encryptedDuplexPreservesAudioBytesAndUnicode() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            String code = JamWire.newCode();
            byte[] audio = new byte[65536]; new java.security.SecureRandom().nextBytes(audio);
            Future<?> host = worker.submit(() -> {
                try (JamWire wire = new JamWire(listener.accept(), code, true)) {
                    JamWire.Packet packet = wire.read();
                    assertEquals("{\"title\":\"Canção 🎵\"}", packet.json);
                    assertArrayEquals(audio, packet.blob);
                    wire.send("{\"ok\":true}", null);
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            try (JamWire guest = new JamWire(new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort()), code.toLowerCase().replace("-", ""), false)) {
                guest.send("{\"title\":\"Canção 🎵\"}", audio);
                assertEquals("{\"ok\":true}", guest.read().json);
            }
            host.get(20, TimeUnit.SECONDS);
        } finally { worker.shutdownNow(); }
    }

    @Test public void incorrectInvitationIsRejected() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Future<Boolean> host = worker.submit(() -> {
                try (Socket socket = listener.accept()) {
                    try (JamWire ignored = new JamWire(socket, "ABCD-EFGH-JKLM", true)) { return false; }
                    catch (IOException expected) { return true; }
                }
            });
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort())) {
                try (JamWire ignored = new JamWire(socket, "ABCD-EFGH-JKLN", false)) { fail("Wrong code accepted"); }
                catch (IOException expected) { }
            }
            assertTrue(host.get(20, TimeUnit.SECONDS));
        } finally { worker.shutdownNow(); }
    }

    @Test public void rejectsOversizedFramesBeforeAllocation() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            String code = JamWire.newCode();
            Future<?> host = worker.submit(() -> {
                try (JamWire wire = new JamWire(listener.accept(), code, true)) {
                    try { wire.read(); fail("Invalid frame accepted"); }
                    catch (IOException expected) { assertTrue(expected.getMessage().contains("Pacote")); }
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
                 JamWire guest = new JamWire(socket, code, false)) {
                DataOutputStream raw = new DataOutputStream(socket.getOutputStream());
                raw.writeInt(Integer.MAX_VALUE); raw.flush();
            }
            host.get(20, TimeUnit.SECONDS);
        } finally { worker.shutdownNow(); }
    }
}
