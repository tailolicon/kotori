package eu.kanade.domain

import eu.kanade.domain.download.anime.interactor.DeleteEpisodeDownload
import eu.kanade.domain.entries.anime.interactor.SetAnimeViewerFlags
import eu.kanade.domain.entries.anime.interactor.SyncSeasonsWithSource
import eu.kanade.domain.entries.anime.interactor.UpdateAnime
import eu.kanade.domain.extension.anime.interactor.GetAnimeExtensionLanguages
import eu.kanade.domain.extension.anime.interactor.GetAnimeExtensionSources
import eu.kanade.domain.extension.anime.interactor.GetAnimeExtensionsByType
import eu.kanade.domain.extension.anime.interactor.TrustAnimeExtension
import eu.kanade.domain.items.episode.interactor.SetSeenStatus
import eu.kanade.domain.items.episode.interactor.SyncEpisodesWithSource
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.source.anime.interactor.GetAnimeSourcesWithFavoriteCount
import eu.kanade.domain.source.anime.interactor.GetEnabledAnimeSources
import eu.kanade.domain.source.anime.interactor.GetLanguagesWithAnimeSources
import eu.kanade.domain.source.anime.interactor.ToggleAnimeIncognito
import eu.kanade.domain.source.anime.interactor.ToggleAnimeSource
import eu.kanade.domain.source.anime.interactor.ToggleAnimeSourcePin
import eu.kanade.domain.track.anime.interactor.AddAnimeTracks
import eu.kanade.domain.track.anime.interactor.RefreshAnimeTracks
import eu.kanade.domain.track.anime.interactor.SyncEpisodeProgressWithTrack
import eu.kanade.domain.track.anime.interactor.TrackEpisode
import eu.kanade.tachiyomi.ui.player.utils.TrackSelect
import mihon.data.repository.anime.AnimeExtensionRepoRepositoryImpl
import mihon.domain.extensionrepo.anime.interactor.CreateAnimeExtensionRepo
import mihon.domain.extensionrepo.anime.interactor.DeleteAnimeExtensionRepo
import mihon.domain.extensionrepo.anime.interactor.GetAnimeExtensionRepo
import mihon.domain.extensionrepo.anime.interactor.GetAnimeExtensionRepoCount
import mihon.domain.extensionrepo.anime.interactor.ReplaceAnimeExtensionRepo
import mihon.domain.extensionrepo.anime.interactor.UpdateAnimeExtensionRepo
import mihon.domain.extensionrepo.anime.repository.AnimeExtensionRepoRepository
import mihon.domain.items.episode.interactor.FilterEpisodesForDownload
import mihon.domain.upcoming.anime.interactor.GetUpcomingAnime
import tachiyomi.data.category.anime.AnimeCategoryRepositoryImpl
import tachiyomi.data.custombutton.CustomButtonRepositoryImpl
import tachiyomi.data.entries.anime.AnimeRepositoryImpl
import tachiyomi.data.history.anime.AnimeHistoryRepositoryImpl
import tachiyomi.data.items.episode.EpisodeRepositoryImpl
import tachiyomi.data.source.anime.AnimeSourceRepositoryImpl
import tachiyomi.data.source.anime.AnimeStubSourceRepositoryImpl
import tachiyomi.data.track.anime.AnimeTrackRepositoryImpl
import tachiyomi.data.updates.anime.AnimeUpdatesRepositoryImpl
import tachiyomi.domain.category.anime.interactor.CreateAnimeCategoryWithName
import tachiyomi.domain.category.anime.interactor.DeleteAnimeCategory
import tachiyomi.domain.category.anime.interactor.GetAnimeCategories
import tachiyomi.domain.category.anime.interactor.GetVisibleAnimeCategories
import tachiyomi.domain.category.anime.interactor.HideAnimeCategory
import tachiyomi.domain.category.anime.interactor.RenameAnimeCategory
import tachiyomi.domain.category.anime.interactor.ReorderAnimeCategory
import tachiyomi.domain.category.anime.interactor.ResetAnimeCategoryFlags
import tachiyomi.domain.category.anime.interactor.SetAnimeCategories
import tachiyomi.domain.category.anime.interactor.SetAnimeDisplayMode
import tachiyomi.domain.category.anime.interactor.SetSortModeForAnimeCategory
import tachiyomi.domain.category.anime.interactor.UpdateAnimeCategory
import tachiyomi.domain.category.anime.repository.AnimeCategoryRepository
import tachiyomi.domain.custombuttons.interactor.CreateCustomButton
import tachiyomi.domain.custombuttons.interactor.DeleteCustomButton
import tachiyomi.domain.custombuttons.interactor.GetCustomButtons
import tachiyomi.domain.custombuttons.interactor.ReorderCustomButton
import tachiyomi.domain.custombuttons.interactor.ToggleFavoriteCustomButton
import tachiyomi.domain.custombuttons.interactor.UpdateCustomButton
import tachiyomi.domain.custombuttons.repository.CustomButtonRepository
import tachiyomi.domain.entries.anime.interactor.AnimeFetchInterval
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.interactor.GetAnimeByUrlAndSourceId
import tachiyomi.domain.entries.anime.interactor.GetAnimeFavorites
import tachiyomi.domain.entries.anime.interactor.GetAnimeWithEpisodesAndSeasons
import tachiyomi.domain.entries.anime.interactor.GetDuplicateLibraryAnime
import tachiyomi.domain.entries.anime.interactor.GetLibraryAnime
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.anime.interactor.ResetAnimeViewerFlags
import tachiyomi.domain.entries.anime.interactor.SetAnimeEpisodeFlags
import tachiyomi.domain.entries.anime.interactor.SetAnimeSeasonFlags
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.history.anime.interactor.GetAnimeHistory
import tachiyomi.domain.history.anime.interactor.GetNextEpisodes
import tachiyomi.domain.history.anime.interactor.RemoveAnimeHistory
import tachiyomi.domain.history.anime.interactor.UpsertAnimeHistory
import tachiyomi.domain.history.anime.repository.AnimeHistoryRepository
import tachiyomi.domain.items.episode.interactor.GetEpisode
import tachiyomi.domain.items.episode.interactor.GetEpisodeByUrlAndAnimeId
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.items.episode.interactor.SetAnimeDefaultEpisodeFlags
import tachiyomi.domain.items.episode.interactor.ShouldUpdateDbEpisode
import tachiyomi.domain.items.episode.interactor.UpdateEpisode
import tachiyomi.domain.items.episode.repository.EpisodeRepository
import tachiyomi.domain.items.season.interactor.GetAnimeSeasonsByParentId
import tachiyomi.domain.items.season.interactor.SetAnimeDefaultSeasonFlags
import tachiyomi.domain.items.season.interactor.ShouldUpdateDbSeason
import tachiyomi.domain.source.anime.interactor.GetAnimeSourcesWithNonLibraryAnime
import tachiyomi.domain.source.anime.interactor.GetRemoteAnime
import tachiyomi.domain.source.anime.repository.AnimeSourceRepository
import tachiyomi.domain.source.anime.repository.AnimeStubSourceRepository
import tachiyomi.domain.track.anime.interactor.DeleteAnimeTrack
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.anime.interactor.GetTracksPerAnime
import tachiyomi.domain.track.anime.interactor.InsertAnimeTrack
import tachiyomi.domain.track.anime.repository.AnimeTrackRepository
import tachiyomi.domain.updates.anime.interactor.GetAnimeUpdates
import tachiyomi.domain.updates.anime.repository.AnimeUpdatesRepository
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addFactory
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

class AnimeDomainModule : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory<AnimeCategoryRepository> { AnimeCategoryRepositoryImpl(get()) }
        addFactory { GetAnimeCategories(get()) }
        addFactory { GetVisibleAnimeCategories(get()) }
        addFactory { ResetAnimeCategoryFlags(get(), get()) }
        addFactory { SetAnimeDisplayMode(get()) }
        addFactory { SetSortModeForAnimeCategory(get(), get()) }
        addFactory { CreateAnimeCategoryWithName(get(), get()) }
        addFactory { RenameAnimeCategory(get()) }
        addFactory { ReorderAnimeCategory(get()) }
        addFactory { UpdateAnimeCategory(get()) }
        addFactory { HideAnimeCategory(get()) }
        addFactory { DeleteAnimeCategory(get(), get(), get()) }
        addSingletonFactory<AnimeRepository> { AnimeRepositoryImpl(get()) }
        addFactory { GetDuplicateLibraryAnime(get()) }
        addFactory { GetAnimeFavorites(get()) }
        addFactory { GetLibraryAnime(get()) }
        addFactory { GetAnimeWithEpisodesAndSeasons(get(), get()) }
        addFactory { GetAnimeByUrlAndSourceId(get()) }
        addFactory { GetAnime(get()) }
        addFactory { GetAnimeSeasonsByParentId(get()) }
        addFactory { GetNextEpisodes(get(), get(), get()) }
        addFactory { GetUpcomingAnime(get()) }
        addFactory { ResetAnimeViewerFlags(get()) }
        addFactory { SetAnimeEpisodeFlags(get()) }
        addFactory { SetAnimeSeasonFlags(get()) }
        addFactory { AnimeFetchInterval(get()) }
        addFactory { SetAnimeDefaultEpisodeFlags(get(), get(), get()) }
        addFactory { SetAnimeDefaultSeasonFlags(get(), get(), get()) }
        addFactory { SetAnimeViewerFlags(get()) }
        addFactory { NetworkToLocalAnime(get(), get()) }
        addFactory { UpdateAnime(get(), get()) }
        addFactory { SetAnimeCategories(get()) }
        addFactory { ShouldUpdateDbSeason() }
        addFactory { SyncSeasonsWithSource(get(), get(), get(), get(), get()) }
        addSingletonFactory<AnimeTrackRepository> { AnimeTrackRepositoryImpl(get()) }
        addFactory { TrackEpisode(get(), get(), get(), get()) }
        addFactory { AddAnimeTracks(get(), get(), get(), get()) }
        addFactory { RefreshAnimeTracks(get(), get(), get(), get()) }
        addFactory { DeleteAnimeTrack(get()) }
        addFactory { GetTracksPerAnime(get()) }
        addFactory { GetAnimeTracks(get()) }
        addFactory { InsertAnimeTrack(get()) }
        addFactory { SyncEpisodeProgressWithTrack(get(), get(), get()) }
        addSingletonFactory<EpisodeRepository> { EpisodeRepositoryImpl(get()) }
        addFactory { GetEpisode(get()) }
        addFactory { GetEpisodesByAnimeId(get()) }
        addFactory { GetEpisodeByUrlAndAnimeId(get()) }
        addFactory { UpdateEpisode(get()) }
        addFactory { SetSeenStatus(get(), get(), get(), get()) }
        addFactory { ShouldUpdateDbEpisode() }
        addFactory { SyncEpisodesWithSource(get(), get(), get(), get(), get(), get(), get(), get()) }
        addFactory { FilterEpisodesForDownload(get(), get(), get()) }
        addSingletonFactory<AnimeHistoryRepository> { AnimeHistoryRepositoryImpl(get()) }
        addFactory { GetAnimeHistory(get()) }
        addFactory { UpsertAnimeHistory(get()) }
        addFactory { RemoveAnimeHistory(get()) }
        addFactory { DeleteEpisodeDownload(get(), get()) }
        addFactory { GetAnimeExtensionsByType(get(), get()) }
        addFactory { GetAnimeExtensionSources(get()) }
        addFactory { GetAnimeExtensionLanguages(get(), get()) }
        addSingletonFactory<AnimeUpdatesRepository> { AnimeUpdatesRepositoryImpl(get()) }
        addFactory { GetAnimeUpdates(get()) }
        addSingletonFactory<AnimeSourceRepository> { AnimeSourceRepositoryImpl(get(), get()) }
        addSingletonFactory<AnimeStubSourceRepository> { AnimeStubSourceRepositoryImpl(get()) }
        addFactory { GetEnabledAnimeSources(get(), get()) }
        addFactory { GetLanguagesWithAnimeSources(get(), get()) }
        addFactory { GetRemoteAnime(get()) }
        addFactory { GetAnimeSourcesWithFavoriteCount(get(), get()) }
        addFactory { GetAnimeSourcesWithNonLibraryAnime(get()) }
        addFactory { ToggleAnimeSource(get()) }
        addFactory { ToggleAnimeSourcePin(get()) }
        addFactory { TrustAnimeExtension(get(), get()) }
        addSingletonFactory<AnimeExtensionRepoRepository> { AnimeExtensionRepoRepositoryImpl(get()) }
        addFactory { GetAnimeExtensionRepo(get()) }
        addFactory { GetAnimeExtensionRepoCount(get()) }
        addFactory { CreateAnimeExtensionRepo(get(), get()) }
        addFactory { DeleteAnimeExtensionRepo(get()) }
        addFactory { ReplaceAnimeExtensionRepo(get()) }
        addFactory { UpdateAnimeExtensionRepo(get(), get()) }
        addFactory { ToggleAnimeIncognito(get()) }
        addFactory { GetAnimeIncognitoState(get(), get(), get()) }
        addSingletonFactory<CustomButtonRepository> { CustomButtonRepositoryImpl(get()) }
        addFactory { CreateCustomButton(get()) }
        addFactory { DeleteCustomButton(get()) }
        addFactory { GetCustomButtons(get()) }
        addFactory { UpdateCustomButton(get()) }
        addFactory { ReorderCustomButton(get()) }
        addFactory { ToggleFavoriteCustomButton(get()) }
        addFactory { TrackSelect(get(), get()) }
    }
}
