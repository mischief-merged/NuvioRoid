package com.nuvio.app.core.memory

import com.nuvio.app.features.downloads.SidecarLocks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryRetentionTest {
    @Test fun metadataResultsHaveFiniteCapacity() {
        checkMaps("features.tmdb.TmdbMetadataService", "enrichmentCache", "episodeCache", "moreLikeThisCache",
            "collectionCache", "trailerCache", "personCache", "entityBrowseCache", "entityHeaderCache", "entityRailCache")
        checkMaps("features.tmdb.TmdbService", "imdbToTmdbCache", "tmdbToImdbCache")
        checkMaps("features.trakt.TraktEpisodeMappingServiceKt", "normalizedTitleCache")
        checkMaps("features.watchprogress.ContinueWatchingEnrichmentCache", "cachedPayloads", "migratedLegacyProfileIds")
        checkMaps("features.details.MetaDetailsRepository", "cachedMetaByRequestKey")
        checkMaps("features.details.ImdbEpisodeRatingsRepository", "cache")
        checkMaps("features.trakt.TraktRelatedRepository", "cache")
        checkMaps("features.trakt.TraktCommentsRepository", "cache")
        checkMaps("features.trakt.TraktEpisodeMappingService", "mappingCache", "reverseMappingCache", "addonEpisodesCache", "traktEpisodesCache")
        checkMaps("features.simkl.SimklRelatedRepository", "cache")
    }

    @Test fun playbackLookupsHaveFiniteCapacity() {
        checkMaps("features.player.skip.SkipIntroRepository", "cache", "animeSkipShowIdCache")
        checkMaps("features.player.skip.SimklIdResolver", "idsCache", "episodeCache", "animeSeasonCache")
        checkMaps("features.player.ParentalGuideRepository", "cache")
        checkMaps("features.debrid.DirectDebridPlaybackResolver", "resolvedCache")
        checkMaps("features.catalog.CatalogRepository", "scrollPositions")
        checkMaps("features.debrid.DebridStreamMetadata", "tokenRegexCache")
        checkMaps("features.trakt.TraktProgressRepository", "episodeProgressFetchedAtMsByContentId", "episodeProgressLastAttemptAtMsByContentId")
    }

    @Test fun localControlsImagesHaveFiniteCapacity() {
        checkMaps("features.player.desktop.WebviewImages", "images")
    }

    @Test fun completedSidecarWritesDoNotRetainFileNamesOrLocks() {
        repeat(2048) { SidecarLocks.withLock("file:/test/video-$it") {} }
        assertEquals(0, map("features.downloads.SidecarLocks", "locks").size)
    }

    private fun checkMaps(className: String, vararg names: String) {
        names.forEach { name ->
            val cache = map(className, name)
            try {
                repeat(2048) { cache["test-$it"] = "test" }
                assertTrue(cache.size <= 256, "$className.$name retained ${cache.size} results")
            } finally { cache.clear() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun map(className: String, name: String): MutableMap<Any, Any?> =
        Class.forName("com.nuvio.app.$className").getDeclaredField(name).let {
            it.isAccessible = true
            it.get(null) as MutableMap<Any, Any?>
        }
}
