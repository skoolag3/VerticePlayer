package com.oneshot.surround;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Bounded, authenticated frames. The invitation secret never travels over the socket. */
final class JamWire implements Closeable {
    static final int MAX_PACKET = 128 * 1024;
    private static final SecureRandom random = new SecureRandom();
    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final SecretKeySpec key;
    private long sentSequence, receivedSequence;

    static String newCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) code.append('-');
            code.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return code.toString();
    }

    JamWire(Socket socket, String code, boolean host) throws Exception {
        this.socket = socket;
        socket.setSoTimeout(12000);
        socket.setTcpNoDelay(true);
        input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        byte[] salt = new byte[32];
        if (host) {
            random.nextBytes(salt);
            output.writeInt(0x564A0001); output.write(salt); output.flush();
        } else {
            if (input.readInt() != 0x564A0001) throw new IOException("Versão de Jam incompatível");
            input.readFully(salt);
        }
        String normalized = code.replaceAll("[^A-Za-z0-9]", "").toUpperCase(java.util.Locale.ROOT);
        if (normalized.length() != 12) throw new IOException("Código da sala inválido");
        PBEKeySpec spec = new PBEKeySpec(normalized.toCharArray(), salt, 30000, 256);
        key = new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES");
        spec.clearPassword();
        if (host) {
            byte[] proof = new byte[32]; input.readFully(proof);
            if (!MessageDigest.isEqual(proof, proof("guest", salt))) throw new IOException("Código da sala incorreto");
            output.write(proof("host", salt)); output.flush();
        } else {
            output.write(proof("guest", salt)); output.flush();
            byte[] proof = new byte[32]; input.readFully(proof);
            if (!MessageDigest.isEqual(proof, proof("host", salt))) throw new IOException("Sala não autenticada");
        }
        socket.setSoTimeout(45000);
    }

    private byte[] proof(String role, byte[] salt) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256"));
        mac.update(role.getBytes(StandardCharsets.UTF_8));
        return mac.doFinal(salt);
    }

    synchronized void send(String json, byte[] blob) throws Exception {
        byte[] header = json.getBytes(StandardCharsets.UTF_8);
        int length = 12 + header.length + (blob == null ? 0 : blob.length);
        if (length > MAX_PACKET - 28 || header.length > 16384) throw new IOException("Pacote grande demais");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(length);
        DataOutputStream frame = new DataOutputStream(buffer);
        frame.writeLong(sentSequence++); frame.writeInt(header.length); frame.write(header);
        if (blob != null) frame.write(blob);
        byte[] nonce = new byte[12]; random.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        byte[] encrypted = cipher.doFinal(buffer.toByteArray());
        output.writeInt(nonce.length + encrypted.length); output.write(nonce); output.write(encrypted); output.flush();
    }

    Packet read() throws Exception {
        int length = input.readInt();
        if (length < 42 || length > MAX_PACKET) throw new IOException("Pacote inválido");
        byte[] nonce = new byte[12]; input.readFully(nonce);
        byte[] encrypted = new byte[length - 12]; input.readFully(encrypted);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        byte[] plain = cipher.doFinal(encrypted);
        DataInputStream frame = new DataInputStream(new ByteArrayInputStream(plain));
        if (frame.readLong() != receivedSequence++) throw new IOException("Sequência de pacotes inválida");
        int headerLength = frame.readInt();
        if (headerLength < 2 || headerLength > 16384 || headerLength > plain.length - 12) throw new IOException("Cabeçalho inválido");
        byte[] header = new byte[headerLength]; frame.readFully(header);
        return new Packet(new String(header, StandardCharsets.UTF_8), Arrays.copyOfRange(plain, 12 + headerLength, plain.length));
    }

    public void close() throws IOException { socket.close(); }
    static final class Packet {
        final String json;
        final byte[] blob;
        Packet(String json, byte[] blob) { this.json = json; this.blob = blob; }
    }
}
