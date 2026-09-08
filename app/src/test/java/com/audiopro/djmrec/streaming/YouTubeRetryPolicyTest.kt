package com.audiopro.djmrec.streaming
import org.junit.Assert.*
import org.junit.Test

class YouTubeRetryPolicyTest {
    @Test fun preparationFailureRetainsDraftButUserStopAndStartedBroadcastFinish() {
        assertTrue(retainPlannedYouTubeBroadcast(LiveStreamStatus.ERROR, false))
        assertFalse(retainPlannedYouTubeBroadcast(LiveStreamStatus.ERROR, true))
        assertFalse(retainPlannedYouTubeBroadcast(LiveStreamStatus.IDLE, false))
    }
}
