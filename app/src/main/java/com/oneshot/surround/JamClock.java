package com.oneshot.surround;

/** Estimates monotonic clock offset from the lowest round-trip sample, without wall-clock assumptions. */
final class JamClock {
    private long bestRtt = Long.MAX_VALUE;
    private long offset;

    void observe(long sent, long now, long hostTime) {
        long rtt = now - sent;
        if (rtt < 0 || rtt > 3000 || rtt >= bestRtt) return;
        bestRtt = rtt;
        offset = hostTime - (sent + rtt / 2);
    }

    long position(long remotePosition, long hostTimestamp, float speed, boolean paused, long now) {
        long age = bestRtt == Long.MAX_VALUE ? 0 : Math.max(0, Math.min(3000, now + offset - hostTimestamp));
        long base = Math.max(0, Math.min(24L * 60 * 60 * 1000, remotePosition));
        return base + (paused ? 0 : (long)(age * Math.max(0.5f, Math.min(1.5f, speed))));
    }
}
