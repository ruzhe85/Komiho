package eu.kanade.domain

import android.app.Application
import eu.kanade.domain.manga.interactor.CreateSortTag
import eu.kanade.domain.manga.interactor.DeleteSortTag
import eu.kanade.domain.manga.interactor.GetSortTag
import eu.kanade.domain.manga.interactor.ReorderSortTag
import eu.kanade.domain.source.interactor.CreateSourceCategory
import eu.kanade.domain.source.interactor.DeleteSourceCategory
import eu.kanade.domain.source.interactor.GetShowLatest
import eu.kanade.domain.source.interactor.GetSourceCategories
import eu.kanade.domain.source.interactor.RenameSourceCategory
import eu.kanade.domain.source.interactor.SetSourceCategories
import eu.kanade.tachiyomi.di.InjektModule
import eu.kanade.tachiyomi.di.addFactory
import eu.kanade.tachiyomi.di.addSingletonFactory
import tachiyomi.data.manga.CustomMangaRepositoryImpl
import tachiyomi.data.manga.MangaMergeRepositoryImpl
import tachiyomi.domain.chapter.interactor.DeleteChapters
import tachiyomi.domain.chapter.interactor.GetChapterByUrl
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.manga.interactor.DeleteByMergeId
import tachiyomi.domain.manga.interactor.DeleteMangaById
import tachiyomi.domain.manga.interactor.DeleteMergeById
import tachiyomi.domain.manga.interactor.GetAllManga
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMangaBySource
import tachiyomi.domain.manga.interactor.GetMergedManga
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.interactor.GetMergedMangaForDownloading
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import tachiyomi.domain.manga.interactor.GetReadMangaNotInLibraryView
import tachiyomi.domain.manga.interactor.InsertMergedReference
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.interactor.UpdateMergedSettings
import tachiyomi.domain.manga.repository.CustomMangaRepository
import tachiyomi.domain.manga.repository.MangaMergeRepository
import uy.kohesive.injekt.api.InjektRegistrar

class SYDomainModule : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addFactory { GetShowLatest(get()) }
        addFactory { SetSourceCategories(get()) }
        addFactory { GetAllManga(get()) }
        addFactory { GetMangaBySource(get()) }
        addFactory { DeleteChapters(get()) }
        addFactory { DeleteMangaById(get()) }
        addFactory { GetChapterByUrl(get()) }
        addFactory { GetSourceCategories(get()) }
        addFactory { CreateSourceCategory(get()) }
        addFactory { RenameSourceCategory(get(), get()) }
        addFactory { DeleteSourceCategory(get()) }
        addFactory { GetSortTag(get()) }
        addFactory { CreateSortTag(get(), get()) }
        addFactory { DeleteSortTag(get(), get()) }
        addFactory { ReorderSortTag(get(), get()) }
        addFactory { GetReadMangaNotInLibraryView(get()) }

        addSingletonFactory<MangaMergeRepository> { MangaMergeRepositoryImpl(get()) }
        addFactory { GetMergedManga(get()) }
        addFactory { GetMergedMangaById(get()) }
        addFactory { GetMergedReferencesById(get()) }
        addFactory { GetMergedChaptersByMangaId(get(), get()) }
        addFactory { InsertMergedReference(get()) }
        addFactory { UpdateMergedSettings(get()) }
        addFactory { DeleteByMergeId(get()) }
        addFactory { DeleteMergeById(get()) }
        addFactory { GetMergedMangaForDownloading(get()) }

        addSingletonFactory<CustomMangaRepository> { CustomMangaRepositoryImpl(get<Application>()) }
        addFactory { GetCustomMangaInfo(get()) }
        addFactory { SetCustomMangaInfo(get()) }
    }
}
