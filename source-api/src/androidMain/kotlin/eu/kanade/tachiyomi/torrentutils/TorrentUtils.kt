package eu.kanade.tachiyomi.torrentutils

import aniyomi.core.common.torrent.DisabledTorrServerException
import aniyomi.core.common.torrent.TorrentServerApi
import eu.kanade.tachiyomi.torrentutils.model.DeadTorrentException
import eu.kanade.tachiyomi.torrentutils.model.TorrentFile
import eu.kanade.tachiyomi.torrentutils.model.TorrentInfo
import kotlinx.coroutines.runBlocking
import uy.kohesive.injekt.injectLazy
import java.net.SocketTimeoutException

object TorrentUtils {
    private val torrentServerApi: TorrentServerApi by injectLazy()

    fun getTorrentInfo(
        url: String,
        title: String,
    ): TorrentInfo = runBlocking {
        try {
            val torrent = torrentServerApi.addTorrent(url, title, "", "", false)
            TorrentInfo(
                torrent.title,
                torrent.fileStats?.map { file ->
                    TorrentFile(file.path, file.id ?: 0, file.length, torrent.hash!!, torrent.trackers ?: emptyList())
                } ?: emptyList(),
                torrent.hash!!,
                torrent.torrentSize!!,
                torrent.trackers ?: emptyList(),
            )
        } catch (_: SocketTimeoutException) {
            throw DeadTorrentException()
        } catch (_: Exception) {
            throw DisabledTorrServerException()
        }
    }
}
