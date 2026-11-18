package com.oneshot.surround;

import org.junit.Test;
import static org.junit.Assert.*;

public class JamClockTest {
    @Test public void compensatesNetworkTimeAndPlaybackSpeed() {
        JamClock clock = new JamClock();
        // Guest midpoint 1010 corresponds to host 5010, so host clock is 4s ahead.
        clock.observe(1000, 1020, 5010);
        assertEquals(10300, clock.position(10000, 5100, 1.5f, false, 1300));
        assertEquals(10000, clock.position(10000, 5100, 1.5f, true, 1300));
    }
    @Test public void ignoresSlowerClockSamplesAndClampsStaleSnapshots() {
        JamClock clock = new JamClock();
        clock.observe(1000, 1020, 5010);
        clock.observe(2000, 2600, 99000);
        assertEquals(10200, clock.position(10000, 5100, 1, false, 1300));
        assertEquals(13000, clock.position(10000, 5100, 1, false, 20000));
    }
    @Test public void uncalibratedClockDoesNotInventAnOffset() {
        assertEquals(42, new JamClock().position(42, 100000, 1, false, 100));
    }
}
