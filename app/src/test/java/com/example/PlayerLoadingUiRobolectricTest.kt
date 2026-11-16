package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.example.data.remote.ProphecySourceDto
import com.example.ui.screens.ErrorOverlay
import com.example.ui.screens.LoadingAndBufferingOverlay
import com.example.ui.theme.ProphecyVerseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerLoadingUiRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val legacyLoadingStrings = listOf(
        "Finding sources...",
        "Validating source...",
        "Resolving playback...",
        "Starting playback...",
        "Buffering...",
        "Loading...",
        "Loading Source",
        "Connecting"
    )

    private fun assertNoLegacyLoadingMessages() {
        legacyLoadingStrings.forEach { legacy ->
            composeRule.onNodeWithText(legacy, substring = true, ignoreCase = false).assertDoesNotExist()
        }
    }

    @Test
    fun loadingOverlay_resolvingState_showsOnlyLoadingAndSpinner() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ProphecyVerseTheme {
                LoadingAndBufferingOverlay(
                    isResolving = true,
                    isBuffering = false,
                    isInPipMode = false
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(32L)

        composeRule.onNodeWithTag("player_loading_overlay").assertIsDisplayed()
        composeRule.onNodeWithTag("player_loading_spinner").assertIsDisplayed()
        composeRule.onNodeWithTag("player_loading_text").assertIsDisplayed().assertTextEquals("Loading")
        assertNoLegacyLoadingMessages()
    }

    @Test
    fun loadingOverlay_bufferingState_showsOnlyLoadingAndSpinner() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ProphecyVerseTheme {
                LoadingAndBufferingOverlay(
                    isResolving = false,
                    isBuffering = true,
                    isInPipMode = false
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(32L)

        composeRule.onNodeWithTag("player_loading_overlay").assertIsDisplayed()
        composeRule.onNodeWithTag("player_loading_spinner").assertIsDisplayed()
        composeRule.onNodeWithTag("player_loading_text").assertIsDisplayed().assertTextEquals("Loading")
        assertNoLegacyLoadingMessages()
    }

    @Test
    fun loadingOverlay_resolvingAndBufferingTogether_showsSingleUnifiedLoadingOverlay() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ProphecyVerseTheme {
                LoadingAndBufferingOverlay(
                    isResolving = true,
                    isBuffering = true,
                    isInPipMode = false
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(32L)

        composeRule.onNodeWithTag("player_loading_overlay").assertIsDisplayed()
        composeRule.onNodeWithTag("player_loading_spinner").assertIsDisplayed()
        composeRule.onNodeWithText("Loading", useUnmergedTree = true).assertIsDisplayed()
        assertNoLegacyLoadingMessages()
    }

    @Test
    fun loadingOverlay_hiddenWhenNotResolvingAndNotBuffering() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ProphecyVerseTheme {
                LoadingAndBufferingOverlay(
                    isResolving = false,
                    isBuffering = false,
                    isInPipMode = false
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(16L)
        composeRule.onNodeWithTag("player_loading_overlay").assertDoesNotExist()
        composeRule.onNodeWithTag("player_loading_text").assertDoesNotExist()
    }

    @Test
    fun loadingOverlay_hiddenWhenInPipMode() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ProphecyVerseTheme {
                LoadingAndBufferingOverlay(
                    isResolving = true,
                    isBuffering = true,
                    isInPipMode = true
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(16L)
        composeRule.onNodeWithTag("player_loading_overlay").assertDoesNotExist()
    }

    @Test
    fun errorOverlay_genuineErrorWhenNotResolving_remainsVisibleWithRetryAction() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ProphecyVerseTheme {
                ErrorOverlay(
                    resolutionError = "Stream unavailable right now",
                    playerError = null,
                    isResolving = false,
                    isInPipMode = false,
                    availableSources = listOf(
                        ProphecySourceDto(id = "src1", label = "Pahe", quality = "1080p"),
                        ProphecySourceDto(id = "src2", label = "Kiwi", quality = "720p")
                    ),
                    activeSource = ProphecySourceDto(id = "src1", label = "Pahe", quality = "1080p"),
                    onRetry = {},
                    onSwitchSource = {}
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(16L)

        composeRule.onNodeWithTag("player_error_overlay").assertIsDisplayed()
        composeRule.onNodeWithText("Stream unavailable right now").assertIsDisplayed()
        composeRule.onNodeWithText("Retry Playback").assertIsDisplayed()
        composeRule.onNodeWithText("Try Alternate Source (720p)").assertIsDisplayed()
    }
}
