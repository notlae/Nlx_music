package com.nlx.music.data.music

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Locks the YouTube Music "like" wire contract: the rate endpoints and the
 * target payload the authenticated POST carries.
 */
class YtMusicLikeTest {

    @Test
    fun likeAndRemoveLikeUseTheAuthenticatedRateEndpoints() {
        assertThat(LIKE_ENDPOINT).isEqualTo("like/like")
        assertThat(REMOVE_LIKE_ENDPOINT).isEqualTo("like/removelike")
        assertThat(LIKE_ENDPOINT).isNotEqualTo(REMOVE_LIKE_ENDPOINT)
    }

    @Test
    fun likeTargetCarriesOnlyTheVideoId() {
        val target = likeTargetBody("dQw4w9WgXcQ")
        assertThat(target.keys).containsExactly("videoId")
        assertThat(target["videoId"]?.jsonPrimitive?.content).isEqualTo("dQw4w9WgXcQ")
    }

    @Test
    fun likeTargetSurvivesASerializationRoundTrip() {
        // The body is inlined into the request JSON, so it must encode cleanly.
        val frame = """
            {"target":${likeTargetBody("dQw4w9WgXcQ")}}
        """.trimIndent()
        val parsed = Json.parseToJsonElement(frame).jsonObject
        assertThat(parsed["target"]?.jsonObject?.get("videoId")?.jsonPrimitive?.content)
            .isEqualTo("dQw4w9WgXcQ")
    }
}
