package com.nlx.music.data.music

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * Regression tests for playlist suggestion shelf detection and filtering.
 *
 * Issue #299: YouTube Music returns "suggested songs to be added to playlist"
 * in recommendation shelves at the end of playlist data or in sectionList continuations.
 * These extra songs do not belong to the remote playlist and cannot be removed.
 */
class PlaylistSuggestionsFilterTest {

    @Test
    fun shelfWithSuggestionsTitleIsIdentifiedAsSuggestion() {
        val titles = listOf(
            "Suggestions",
            "Suggested songs",
            "Suggestions for you",
            "Recommended songs",
            "Vorschläge",
            "Sugerencias",
            "Recommandations",
            "Рекомендации",
            "추천",
            "おすすめ",
        )

        for (title in titles) {
            val json = """
                {
                    "title": {
                        "runs": [
                            { "text": "$title" }
                        ]
                    },
                    "contents": []
                }
            """.trimIndent()
            val shelf = Json.parseToJsonElement(json).jsonObject
            assertThat(isSuggestionShelf(shelf)).isTrue()
        }
    }

    @Test
    fun shelfWithHeaderRendererSuggestionsTitleIsIdentified() {
        val json = """
            {
                "header": {
                    "musicShelfHeaderRenderer": {
                        "title": {
                            "runs": [
                                { "text": "Suggested songs" }
                            ]
                        }
                    }
                },
                "contents": []
            }
        """.trimIndent()
        val shelf = Json.parseToJsonElement(json).jsonObject
        assertThat(isSuggestionShelf(shelf)).isTrue()
    }

    @Test
    fun shelfWithAllAddActionsIsIdentifiedAsSuggestion() {
        val json = """
            {
                "contents": [
                    {
                        "musicResponsiveListItemRenderer": {
                            "playlistEditEndpoint": {
                                "actions": [
                                    { "action": "ACTION_ADD_VIDEO" }
                                ]
                            }
                        }
                    }
                ]
            }
        """.trimIndent()
        val shelf = Json.parseToJsonElement(json).jsonObject
        assertThat(isSuggestionShelf(shelf)).isTrue()
    }

    @Test
    fun normalPlaylistShelfIsNotIdentifiedAsSuggestion() {
        val json = """
            {
                "playlistId": "PL1234567890",
                "contents": [
                    {
                        "musicResponsiveListItemRenderer": {
                            "playlistItemData": {
                                "videoId": "vid123",
                                "playlistSetVideoId": "set123"
                            },
                            "playlistEditEndpoint": {
                                "actions": [
                                    { "action": "ACTION_REMOVE_VIDEO_BY_SET_VIDEO_ID", "setVideoId": "set123" }
                                ]
                            }
                        }
                    }
                ]
            }
        """.trimIndent()
        val shelf = Json.parseToJsonElement(json).jsonObject
        assertThat(isSuggestionShelf(shelf)).isFalse()
    }

    @Test
    fun shelfWithBottomSuggestionEndpointIsIdentifiedAsSuggestion() {
        val json = """
            {
                "contents": [],
                "bottomEndpoint": {
                    "reloadContinuationData": {
                        "continuation": "token_for_suggestions"
                    }
                }
            }
        """.trimIndent()
        val shelf = Json.parseToJsonElement(json).jsonObject
        assertThat(isSuggestionShelf(shelf)).isTrue()
    }
}
