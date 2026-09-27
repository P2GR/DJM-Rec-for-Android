package com.audiopro.djmrec.streaming
import org.junit.Assert.*
import org.junit.Test

class YouTubeRetryPolicyTest {
    @Test fun preparationFailureRetainsDraftButUserStopAndStartedBroadcastFinish() {
        assertTrue(retainPlannedYouTubeBroadcast(LiveStreamStatus.ERROR, false))
        assertFalse(retainPlannedYouTubeBroadcast(LiveStreamStatus.ERROR, true))
        assertFalse(retainPlannedYouTubeBroadcast(LiveStreamStatus.IDLE, false))
    }

    @Test fun failureAfterGoingLiveKeepsTheBroadcastForResume() {
        assertEquals(YouTubeStreamEndAction.KEEP_INTERRUPTED, youtubeStreamEndAction(LiveStreamStatus.ERROR, true))
        assertEquals(YouTubeStreamEndAction.KEEP_PLANNED, youtubeStreamEndAction(LiveStreamStatus.ERROR, false))
        assertEquals(YouTubeStreamEndAction.FINISH, youtubeStreamEndAction(LiveStreamStatus.IDLE, true))
        assertEquals(YouTubeStreamEndAction.FINISH, youtubeStreamEndAction(LiveStreamStatus.IDLE, false))
    }
}
