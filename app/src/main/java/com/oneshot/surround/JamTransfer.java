package com.oneshot.surround;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;
import org.json.JSONObject;

/** Original file transport, bounded cache, and SHA-256 verification before decoding. */
final class JamTransfer implements Closeable {
    static final long MAX_BYTES = 512L * 1024 * 1024;
    private final Context context;
    private final File directory;
    private File incoming;
    private FileOutputStream output;
    private MessageDigest digest;
    private long expectedBytes, receivedBytes;
    private String expectedHash;
    private JSONObject metadata;
    private File playable;

    JamTransfer(Context context) {
        this.context = context;
        directory = new File(context.getCacheDir(), "jam");
        directory.mkdirs();
        File[] stale = directory.listFiles();
        if (stale != null) for (File file : stale) file.delete();
    }

    void send(JamWire wire, Uri uri, String title, String artist, String token) throws Exception {
        File staged = new File(directory, "sending.part");
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (InputStream source = context.getContentResolver().openInputStream(uri);
                 OutputStream target = new FileOutputStream(staged)) {
                if (source == null) throw new IOException("Não foi possível abrir a música");
                byte[] buffer = new byte[65536]; int count;
                while ((count = source.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    size += count;
                    if (size > MAX_BYTES || directory.getUsableSpace() < count + 8L * 1024 * 1024) throw new IOException("Arquivo grande demais ou pouco espaço livre");
                    target.write(buffer, 0, count); sha.update(buffer, 0, count);
                }
            }
            if (size == 0) throw new IOException("Arquivo vazio");
            wire.send(new JSONObject().put("type", "file").put("track", token).put("bytes", size)
                .put("sha256", hex(sha.digest())).put("title", title).put("artist", artist).toString(), null);
            try (InputStream source = new FileInputStream(staged)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = source.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    wire.send("{\"type\":\"chunk\"}", Arrays.copyOf(buffer, count));
                }
            }
            wire.send(new JSONObject().put("type", "end").put("track", token).toString(), null);
        } finally { staged.delete(); }
    }

    void begin(JSONObject header) throws Exception {
        cancelIncoming();
        expectedBytes = header.getLong("bytes");
        expectedHash = header.getString("sha256");
        if (expectedBytes <= 0 || expectedBytes > MAX_BYTES || !expectedHash.matches("[a-f0-9]{64}")
                || directory.getUsableSpace() < expectedBytes + 8L * 1024 * 1024) throw new IOException("Sem espaço para receber esta música");
        metadata = header;
        incoming = File.createTempFile("receive-", ".part", directory);
        output = new FileOutputStream(incoming);
        digest = MessageDigest.getInstance("SHA-256");
        receivedBytes = 0;
    }

    int chunk(byte[] bytes) throws IOException {
        if (output == null || bytes.length == 0 || receivedBytes + bytes.length > expectedBytes) throw new IOException("Transferência inválida");
        output.write(bytes); digest.update(bytes); receivedBytes += bytes.length;
        return (int)(100 * receivedBytes / expectedBytes);
    }

    Received finish(String token) throws Exception {
        if (output == null || !token.equals(metadata.getString("track"))) throw new IOException("Faixa inesperada");
        output.close(); output = null;
        if (receivedBytes != expectedBytes || !expectedHash.equals(hex(digest.digest()))) {
            cancelIncoming(); throw new IOException("O arquivo recebido não passou na verificação SHA-256");
        }
        File ready = new File(directory, "track-" + java.util.UUID.randomUUID() + ".audio");
        if (!incoming.renameTo(ready)) throw new IOException("Falha ao salvar música");
        incoming = null;
        File previous = playable;
        playable = ready;
        return new Received(ready, metadata.optString("title", "Jam"), metadata.optString("artist", "Vértice"), previous);
    }

    private void cancelIncoming() {
        if (output != null) try { output.close(); } catch (IOException ignored) { }
        output = null;
        if (incoming != null) incoming.delete();
        incoming = null;
    }
    public void close() {
        cancelIncoming();
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) file.delete();
    }
    static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte item : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return value.toString();
    }
    static final class Received {
        final File file;
        final String title, artist;
        final File previous;
        Received(File file, String title, String artist, File previous) {
            this.file = file; this.title = title; this.artist = artist; this.previous = previous;
        }
        Uri uri(Context context) { return androidx.core.content.FileProvider.getUriForFile(context, context.getPackageName() + ".jam", file); }
    }
}
