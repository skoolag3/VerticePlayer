package com.oneshot.surround;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.security.MessageDigest;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JamTransferTest {
    @Test public void receivedOriginalIsVerifiedAndStoredInPrivateCache() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        byte[] audio = new byte[120000]; new java.security.SecureRandom().nextBytes(audio);
        try (JamTransfer transfer = new JamTransfer(context)) {
            transfer.begin(header(audio));
            transfer.chunk(java.util.Arrays.copyOfRange(audio, 0, 60000));
            assertEquals(100, transfer.chunk(java.util.Arrays.copyOfRange(audio, 60000, audio.length)));
            JamTransfer.Received track = transfer.finish("track1");
            assertEquals("Canção", track.title);
            assertEquals(new File(context.getCacheDir(), "jam").getCanonicalFile(), track.file.getParentFile().getCanonicalFile());
            try (InputStream input = new FileInputStream(track.file)) {
                ByteArrayOutputStream received = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) received.write(buffer, 0, count);
                assertArrayEquals(audio, received.toByteArray());
            }
        }
    }

    @Test public void corruptedAudioIsRejected() throws Exception {
        byte[] audio = {1, 2, 3, 4};
        try (JamTransfer transfer = new JamTransfer(RuntimeEnvironment.getApplication())) {
            transfer.begin(header(audio)); transfer.chunk(new byte[]{1, 2, 3, 5});
            try { transfer.finish("track1"); fail("Corrupted audio accepted"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("SHA-256")); }
        }
    }

    @Test public void oversizedFilesAreRejectedBeforeWriting() throws Exception {
        try (JamTransfer transfer = new JamTransfer(RuntimeEnvironment.getApplication())) {
            JSONObject header = header(new byte[]{1}).put("bytes", JamTransfer.MAX_BYTES + 1);
            try { transfer.begin(header); fail("Oversized file accepted"); }
            catch (IOException expected) { }
        }
    }

    @Test public void automaticResumeCannotClearHeadphoneSafetyLatch() {
        FfmpegPcmPlayer player = PlaybackService.getPlayer(RuntimeEnvironment.getApplication());
        player.muteForHeadphoneDisconnect(); player.resumeAutomatic();
        assertTrue(player.isRouteMuted()); assertTrue(player.isPaused());
        player.allowOutput(); player.stop();
    }

    private JSONObject header(byte[] audio) throws Exception {
        return new JSONObject().put("track", "track1").put("bytes", audio.length)
            .put("sha256", JamTransfer.hex(MessageDigest.getInstance("SHA-256").digest(audio)))
            .put("title", "Canção").put("artist", "Artista");
    }
}
