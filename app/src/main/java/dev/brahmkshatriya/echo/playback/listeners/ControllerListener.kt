package dev.brahmkshatriya.echo.playback.listeners

import android.app.Service
import android.content.Context
import android.media.AudioManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import dev.brahmkshatriya.echo.common.ControllerExtension
import dev.brahmkshatriya.echo.common.clients.ControllerClient
import dev.brahmkshatriya.echo.extensions.ControllerServiceHelper
import dev.brahmkshatriya.echo.extensions.get
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
class ControllerListener(
    player: Player,
    service: Service,
    private val scope: CoroutineScope,
    private val controllerExtensions: MutableStateFlow<List<ControllerExtension>?>,
    private val throwableFlow: MutableSharedFlow<Throwable>
) : PlayerListener(player) {
    private var audioManager: AudioManager = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val serviceHelper = ControllerServiceHelper(service)
    private val needsService: AtomicBoolean = AtomicBoolean(false)

    init {
        scope.launch {
            controllerExtensions.collect { extensions ->
                extensions?.forEach {
                    launch {
                        registerController(it)
                    }
                }
            }
        }
    }

    fun onDestroy() {
        serviceHelper.stopService()
    }

    private suspend fun registerController(extension: ControllerExtension) {
        extension.get<ControllerClient, Unit>(throwableFlow) {
            if (runsDuringPause) {
                if (!needsService.get()) {
                    serviceHelper.startService(player)
                }
                needsService.set(true)
            }
            onPlayRequest = {
                tryOnMain(Player.COMMAND_PLAY_PAUSE) {
                    if (needsService.get()) {
                        serviceHelper.play()
                    } else {
                        player.play()
                    }
                }
            }
            onPauseRequest = {
                tryOnMain(Player.COMMAND_PLAY_PAUSE) {
                    if (needsService.get()) {
                        serviceHelper.pause()
                    } else {
                        player.pause()
                    }
                }
            }
            onNextRequest = {
                tryOnMain(Player.COMMAND_SEEK_TO_NEXT) {
                    if (needsService.get()) {
                        serviceHelper.seekToNext()
                    } else {
                        player.seekToNextMediaItem()
                    }
                }
            }
            onPreviousRequest = {
                tryOnMain(Player.COMMAND_SEEK_TO_PREVIOUS) {
                    if (needsService.get()) {
                        serviceHelper.seekToPrevious()
                    } else {
                        player.seekToPreviousMediaItem()
                    }
                }
            }
            onSeekRequest = { position ->
                tryOnMain(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) {
                    if (needsService.get()) {
                        serviceHelper.seekTo(position.toLong())
                    } else {
                        player.seekTo(position.toLong())
                    }
                }
            }
            onSeekToMediaItemRequest = { index ->
                tryOnMain(Player.COMMAND_SEEK_TO_MEDIA_ITEM) {
                    if (needsService.get()) {
                        serviceHelper.seekToMediaItem(index)
                    } else {
                        player.seekTo(index, 0)
                    }
                }
            }
            onMovePlaylistItemRequest = { fromIndex, toIndex ->
                tryOnMain(Player.COMMAND_CHANGE_MEDIA_ITEMS) {
                    if (needsService.get()) {
                        serviceHelper.moveMediaItem(fromIndex, toIndex)
                    } else {
                        player.moveMediaItem(fromIndex, toIndex)
                    }
                }
            }
            onRemovePlaylistItemRequest = { index ->
                tryOnMain(Player.COMMAND_CHANGE_MEDIA_ITEMS) {
                    if (needsService.get()) {
                        serviceHelper.removeMediaItem(index)
                    } else {
                        player.removeMediaItem(index)
                    }
                }
            }
            onShuffleModeRequest = { enabled ->
                tryOnMain(Player.COMMAND_SET_SHUFFLE_MODE) {
                    if (needsService.get()) {
                        serviceHelper.setShuffleMode(enabled)
                    } else {
                        player.shuffleModeEnabled = enabled
                    }
                }
            }
            onRepeatModeRequest = { repeatMode ->
                tryOnMain(Player.COMMAND_SET_REPEAT_MODE) {
                    if (needsService.get()) {
                        serviceHelper.setRepeatMode(repeatMode)
                    } else {
                        player.repeatMode = repeatMode
                    }
                }
            }
            onVolumeRequest = { volume ->
                tryOnMain(-1) { // not an exoplayer command
                    val denormalized =
                        volume * audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, denormalized.toInt(), 0)
                }
            }
        }
    }

    private suspend fun ControllerClient.tryOnMain(
        command: Int,
        block: suspend ControllerClient.() -> Unit
    ) {
        withContext(Dispatchers.Main.immediate) {
            try {
                if (command == -1 || player.isCommandAvailable(command) == true) {
                    block()
                }
            } catch (e: Exception) {
                throwableFlow.emit(e)
            }
        }
    }

    private fun notifyControllers(block: suspend ControllerClient.() -> Unit) {
        val controllers = controllerExtensions.value?.filter { it.metadata.enabled } ?: emptyList()
        scope.launch {
            controllers.forEach {
                launch {
                    it.get<ControllerClient, Unit>(throwableFlow) { block() }
                }
            }
        }
    }

    private fun updatePlaylist() {
        val playlist = List(player.mediaItemCount) { index ->
            player.getMediaItemAt(index).track
        }

        notifyControllers {
            onPlaylistChanged(playlist)
        }
    }

    private fun getVolume(): Double {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return volume.toDouble() / maxVolume.toDouble()
    }


    override fun onRenderedFirstFrame() {
        super.onRenderedFirstFrame()
        notifyControllers {
            onVolumeChanged(getVolume())
        }
    }

    override fun onTrackStart(mediaItem: MediaItem) {
        val isPlaying = player.isPlaying
        val position = player.currentPosition.toDouble()
        val track = mediaItem.track

        notifyControllers {
            onPlaybackStateChanged(isPlaying, position, track)
        }
    }

    override fun onTracksChanged(tracks: Tracks) {
        super.onTracksChanged(tracks)
        updatePlaylist()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        super.onTimelineChanged(timeline, reason)
        updatePlaylist()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super.onMediaItemTransition(mediaItem, reason)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        super.onIsPlayingChanged(isPlaying)
        val position = player.currentPosition.toDouble()
        val track = player.currentMediaItem?.track ?: return

        notifyControllers {
            onPlaybackStateChanged(isPlaying, position, track)
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        super.onShuffleModeEnabledChanged(shuffleModeEnabled)
        val repeatMode = player.repeatMode

        notifyControllers {
            onPlaybackModeChanged(shuffleModeEnabled, repeatMode)
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        super.onRepeatModeChanged(repeatMode)
        val shuffleModeEnabled = player.shuffleModeEnabled

        notifyControllers {
            onPlaybackModeChanged(shuffleModeEnabled, repeatMode)
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        super.onPositionDiscontinuity(oldPosition, newPosition, reason)
        val position = newPosition.positionMs.toDouble()

        notifyControllers {
            onPositionChanged(position)
        }
    }

    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) {
        super.onDeviceVolumeChanged(volume, muted)
        notifyControllers {
            onVolumeChanged(getVolume())
        }
    }
}