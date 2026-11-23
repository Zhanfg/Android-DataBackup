package com.xayah.databackup.feature.update

import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparisonTest {
    @Test
    fun stableBeatsPreviewOfSameCoreVersion() {
        assertTrue(compareVersionNames("3.0.0", "3.0.0-preview.3") > 0)
    }

    @Test
    fun newerPreviewBeatsOlderPreview() {
        assertTrue(compareVersionNames("v3.0.0-preview.4", "3.0.0-preview.3") > 0)
    }

    @Test
    fun previewDoesNotBeatStableOfSameCoreVersion() {
        assertTrue(compareVersionNames("3.0.0-preview.4", "3.0.0") < 0)
    }

    @Test
    fun nextPatchBeatsCurrentPreview() {
        assertTrue(compareVersionNames("3.0.1", "3.0.0-preview.99") > 0)
    }
}
