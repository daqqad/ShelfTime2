package kaf.audiobookshelfwearos.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import kaf.audiobookshelfwearos.R
import kaf.audiobookshelfwearos.app.ApiHandler
import kaf.audiobookshelfwearos.app.MainApp
import kaf.audiobookshelfwearos.app.activities.BookListActivity
import kaf.audiobookshelfwearos.app.activities.ChapterListActivity
import kaf.audiobookshelfwearos.app.activities.PlayerActivity
import kaf.audiobookshelfwearos.app.data.Chapter
import kaf.audiobookshelfwearos.app.data.LibraryItem
import kaf.audiobookshelfwearos.app.data.room.AppDatabase
import kaf.audiobookshelfwearos.app.userdata.UserDataManager
import kaf.audiobookshelfwearos.app.utils.NetworkConnectivityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Timer
import java.util.TimerTask
import kotlin.math.abs


class PlayerService : MediaSessionService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val loadMutex = Mutex()

    private val binder = LocalBinder()
    private lateinit var exoPlayer: ExoPlayer
    private val START_OFFSET_SECONDS = 5
    private var mediaSession: MediaSession? = null
    private lateinit var notificationManager: NotificationManagerCompat

    private var playbackStartTime: Long = 0
    private var totalPlaybackTime: Long = 0
    private var ONGOING_NOTIFICATION_ID: Int = 151
    private var CHANNEL_NAME: String = "Player"

    private var audiobook = LibraryItem()
    private lateinit var db: AppDatabase
    private lateinit var userDataManager: UserDataManager

    // New fields for periodic progress saving and network monitoring
    private var progressSaveTimer: Timer? = null
    private val PROGRESS_SAVE_INTERVAL = 30000L // 30 seconds
    private lateinit var networkConnectivityManager: NetworkConnectivityManager
    private var lastSavedPosition: Double = 0.0

    inner class LocalBinder : Binder() {
        fun getService(): PlayerService = this@PlayerService
    }

    override fun onBind(intent: Intent?): IBinder {
        super.onBind(intent)
        return binder
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        userDataManager = UserDataManager(this)
        createChannel(this)
        notificationManager = NotificationManagerCompat.from(applicationContext)
        db = (applicationContext as MainApp).database
        
        // Initialize network connectivity monitoring
        networkConnectivityManager = NetworkConnectivityManager(this) {
            scope.launch { // Ensure syncPendingProgress is launched in a coroutine
                syncPendingProgress()
            }
        }
        networkConnectivityManager.startMonitoring()
        
        createPlayer()
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
    }

    private fun createChannel(context: Context) {
        val mNotificationManager =
            context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        // The id of the channel.
        // The user-visible name of the channel.
        val name: CharSequence = "Player"
        // The user-visible description of the channel.
        val description: String = "Player"
        val importance = NotificationManager.IMPORTANCE_LOW
        val mChannel = NotificationChannel(CHANNEL_NAME, name, importance)
        // Configure the notification channel.
        mChannel.description = description
        mChannel.setShowBadge(true)
        mChannel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        mNotificationManager.createNotificationChannel(mChannel)
    }

    private fun generateOngoingActivityNotification() {
        if (!exoPlayer.isPlaying) {
            notificationManager.cancel(ONGOING_NOTIFICATION_ID)
            return
        }

        // Main steps for building a BIG_TEXT_STYLE notification:
        //      0. Get data
        //      1. Create Notification Channel for O+
        //      2. Build the BIG_TEXT_STYLE
        //      3. Set up Intent / Pending Intent for notification
        //      4. Build and issue the notification

        // 0. Get data (note, the main notification text comes from the parameter above).
        val titleText = getString(R.string.app_name)

        // 2. Build the BIG_TEXT_STYLE.
        val bigTextStyle = NotificationCompat.BigTextStyle()
            .bigText("Playing")
            .setBigContentTitle(titleText)

        // 3. Set up main Intent/Pending Intents for notification with full back stack.
        val activeBookId = audiobook.id.ifEmpty { userDataManager.lastPlayedItemId }
        val bookListIntent = Intent(this, BookListActivity::class.java)
        val chapterListIntent = Intent(this, ChapterListActivity::class.java).apply {
            putExtra("id", activeBookId)
        }
        val launchActivityIntent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("id", activeBookId)
        }

        val activityPendingIntent = TaskStackBuilder.create(this)
            .addNextIntent(bookListIntent)
            .apply {
                if (activeBookId.isNotEmpty()) {
                    addNextIntent(chapterListIntent)
                }
            }
            .addNextIntent(launchActivityIntent)
            .getPendingIntent(
                0,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ) ?: PendingIntent.getActivity(
                this,
                0,
                launchActivityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        // 4. Build and issue the notification.
        val notificationCompatBuilder =
            NotificationCompat.Builder(applicationContext, CHANNEL_NAME)

        val notificationBuilder = notificationCompatBuilder
            .setStyle(bigTextStyle)
            .setContentTitle(titleText)
            .setContentText("Playing")
            .setSmallIcon(R.drawable.notification)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            // Makes Notification an Ongoing Notification (a Notification with a background task).
            .setOngoing(true)
            // For an Ongoing Activity, used to decide priority on the watch face.
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Create an Ongoing Activity.
        val ongoingActivityStatus = Status.Builder()
            // Sets the text used across various surfaces.
            .addTemplate("Playing")
            .build()

        val ongoingActivity =
            OngoingActivity.Builder(
                applicationContext,
                ONGOING_NOTIFICATION_ID,
                notificationBuilder
            )
                // Sets icon that will appear on the watch face in active mode. If it isn't set,
                // the watch face will use the static icon in active mode.
                .setAnimatedIcon(R.drawable.notification)
                // Sets the icon that will appear on the watch face in ambient mode.
                // Falls back to Notification's smallIcon if not set. If neither is set,
                // an Exception is thrown.
                .setStaticIcon(R.drawable.notification)
                // Sets the tap/touch event, so users can re-enter your app from the
                // other surfaces.
                // Falls back to Notification's contentIntent if not set. If neither is set,
                // an Exception is thrown.
                .setTouchIntent(activityPendingIntent)
                // In our case, sets the text used for the Ongoing Activity (more options are
                // available for timers and stop watches).
                .setStatus(ongoingActivityStatus)
                .build()

        // Applies any Ongoing Activity updates to the notification builder.
        // This method should always be called right before you build your notification,
        // since an Ongoing Activity doesn't hold references to the context.
        ongoingActivity.apply(applicationContext)


        notificationManager.notify(ONGOING_NOTIFICATION_ID, notificationBuilder.build())
    }

    @UnstableApi
    private fun createPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                updateUIMetadata()
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                super.onMediaMetadataChanged(mediaMetadata)
                Timber.d("mediaMetadata - " + mediaMetadata.trackNumber)
                mediaMetadata.trackNumber?.minus(1)
                updateUIMetadata()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                super.onPlayWhenReadyChanged(playWhenReady, reason)
                generateOngoingActivityNotification()
            }

            override fun onPlaybackStateChanged(state: Int) {
                generateOngoingActivityNotification()

                when (state) {
                    Player.STATE_BUFFERING -> {
                        Timber
                            .d("ExoPlayer is buffering")

                        val intent = Intent("$packageName.ACTION_BUFFERING")
                        sendBroadcast(intent)
                    }

                    Player.STATE_READY -> {
                        Timber.d("ExoPlayer is ready " + exoPlayer.currentPosition)
                        updateUIMetadata()
                    }

                    Player.STATE_ENDED -> {
                        Timber.d("ExoPlayer has ended")
                        sendBroadcast(Intent("$packageName.ACTION_TRACK_ENDED"))
                    }

                    Player.STATE_IDLE -> {
                        Timber.d("ExoPlayer in idle")
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    Timber.tag("PlayerService").d("ExoPlayer is playing")
                    playbackStartTime = System.currentTimeMillis()
                    startPeriodicProgressSaving()
                    sendBroadcast(Intent("$packageName.ACTION_PLAYING"))
                } else {
                    Timber.tag("PlayerService").d("ExoPlayer is paused")
                    stopPeriodicProgressSaving()
                    saveProgress() // Final save on pause
                    sendBroadcast(Intent("$packageName.ACTION_PAUSED"))
                    val currentTime = System.currentTimeMillis()
                    totalPlaybackTime += currentTime - playbackStartTime
                    Timber.tag("PlayerService")
                        .d("%s seconds", "Total playback time: ${totalPlaybackTime / 1000}")
                }
            }
        })
    }

    // Enhanced saveProgress method with periodic flag
    private fun saveProgress(isPeriodicSave: Boolean = false) {
        val currentPosition = getCurrentTotalPositionInS()
        Timber.d("Saving progress - periodic: $isPeriodicSave, position: $currentPosition")
        
        Timber.d("getCurrentTotalPositionInS " + getCurrentTotalPositionInS())
        Timber.d("progress " + audiobook.userProgress.progress)
        Timber.d("duration " + audiobook.userProgress.duration)
        Timber.d("episodeId " + audiobook.userProgress.episodeId)
        Timber.d("id " + audiobook.userProgress.id)
        Timber.d("currentTime " + audiobook.userProgress.currentTime)
        Timber.d("lastUpdate " + audiobook.userProgress.lastUpdate)

        audiobook.userProgress.lastUpdate = System.currentTimeMillis()
        audiobook.userProgress.currentTime = currentPosition
        audiobook.userProgress.toUpload = true
        audiobook.userProgress.libraryItemId = audiobook.id
        
        scope.launch(Dispatchers.IO) {
            try {
                db.libraryItemDao().insertLibraryItem(audiobook)
                
                // Attempt immediate sync based on conditions
                val shouldAttemptSync = when {
                    !isPeriodicSave -> true // Always try for manual saves
                    networkConnectivityManager.isNetworkAvailable() -> true // Try if online
                    else -> false // Skip for periodic saves when offline
                }
                
                if (shouldAttemptSync) {
                    val success = ApiHandler(this@PlayerService).updateProgress(audiobook.userProgress)
                    if (success) {
                        Timber.d("Progress synced successfully")
                    } else if (!isPeriodicSave) {
                        Timber.d("Progress sync failed, will retry when connectivity is restored")
                    }
                } else {
                    Timber.d("Skipping sync attempt - offline periodic save")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error saving progress")
            }
        }
    }

    // New method: Start periodic saving
    private fun startPeriodicProgressSaving() {
        stopPeriodicProgressSaving() // Prevent duplicate timers
        
        progressSaveTimer = Timer("ProgressSaveTimer", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    // Switch to the main thread to access ExoPlayer
                    scope.launch(Dispatchers.Main) {
                        if (exoPlayer.isPlaying) {
                            val currentPosition = getCurrentTotalPositionInS()
                            // Only save if position changed significantly (avoid unnecessary writes)
                            if (abs(currentPosition - lastSavedPosition) > 5.0) { // 5 second threshold
                                // saveProgress handles DB/network ops using Dispatchers.IO
                                saveProgress(isPeriodicSave = true)
                                lastSavedPosition = currentPosition
                            }
                        }
                    }
                }
            }, PROGRESS_SAVE_INTERVAL, PROGRESS_SAVE_INTERVAL)
        }
        Timber.d("Started periodic progress saving")
    }

    // New method: Stop periodic saving
    private fun stopPeriodicProgressSaving() {
        progressSaveTimer?.cancel()
        progressSaveTimer = null
        Timber.d("Stopped periodic progress saving")
    }

    // New method: Sync pending progress
    private suspend fun syncPendingProgress() {
        withContext(Dispatchers.IO) {
            try {
                val pendingItems = db.libraryItemDao().getItemsWithPendingSync()
                Timber.d("Found ${pendingItems.size} items with pending progress sync")
                
                var successCount = 0
                for (item in pendingItems) {
                    try {
                        val success = ApiHandler(this@PlayerService).updateProgress(item.userProgress)
                        if (success) {
                            successCount++
                            Timber.d("Successfully synced progress for item: ${item.id}")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to sync progress for item: ${item.id}")
                    }
                }
                
                if (successCount > 0) {
                    Timber.d("Successfully synced $successCount out of ${pendingItems.size} pending items")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error during pending progress sync")
            }
        }
    }

    fun getCurrentChapterTitle(): String {
        val timeInS = getCurrentTotalPositionInS() + START_OFFSET_SECONDS + 1
        for (chapter in audiobook.media.chapters) {
            if (timeInS >= chapter.start && timeInS < chapter.end) {
                return chapter.title
            }
        }
        return audiobook.title.ifEmpty { audiobook.media.metadata.title ?: "" }
    }

    fun getCurrentBookId(): String {
        return audiobook.id.ifEmpty { userDataManager.lastPlayedItemId }
    }

    fun isPlaying(): Boolean {
        return ::exoPlayer.isInitialized && exoPlayer.isPlaying
    }

    fun isBuffering(): Boolean {
        return ::exoPlayer.isInitialized && exoPlayer.playbackState == Player.STATE_BUFFERING
    }

    fun updateUIMetadata() {
        if (!::exoPlayer.isInitialized) return
        if (audiobook.id.isEmpty()) {
            return
        }
        if (exoPlayer.playbackState == Player.STATE_BUFFERING) {
            val intent = Intent("$packageName.ACTION_BUFFERING")
            sendBroadcast(intent)
        }

        val chapterTitle = getCurrentChapterTitle()
        val intent = Intent("$packageName.ACTION_UPDATE_METADATA").apply {
            putExtra("CHAPTER_TITLE", chapterTitle)
        }
        sendBroadcast(intent)
        if (exoPlayer.isPlaying) {
            sendBroadcast(Intent("$packageName.ACTION_PLAYING"))
        } else if (exoPlayer.playbackState != Player.STATE_BUFFERING) {
            sendBroadcast(Intent("$packageName.ACTION_PAUSED"))
        }
    }

    private fun getCurrentTotalPositionInS(): Double {
        if (!::exoPlayer.isInitialized || audiobook.media.tracks.isEmpty())
            return 0.0
        val index = exoPlayer.currentMediaItemIndex.coerceIn(0, audiobook.media.tracks.lastIndex)
        val track = audiobook.media.tracks[index]
        return track.startOffset + exoPlayer.currentPosition.coerceAtLeast(0L) / 1000.0
    }

    // The user dismissed the app from the recent tasks
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player ?: return
        if (!player.playWhenReady
            || player.mediaItemCount == 0
            || player.playbackState == Player.STATE_ENDED
        ) {
            // Stop the service if not playing, continue playing in the background
            // otherwise.
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    @OptIn(UnstableApi::class)
    private fun ensureAudiobookLoaded(
        requestedId: String? = null,
        autoPlay: Boolean = false,
        onReady: () -> Unit = {}
    ) {
        scope.launch {
            loadMutex.withLock {
                val alreadyLoaded = withContext(Dispatchers.Main) {
                    ::exoPlayer.isInitialized &&
                        audiobook.id.isNotEmpty() &&
                        exoPlayer.mediaItemCount > 0 &&
                        (requestedId.isNullOrEmpty() || audiobook.id == requestedId)
                }
                if (alreadyLoaded) {
                    withContext(Dispatchers.Main) {
                        if (autoPlay && !exoPlayer.isPlaying) {
                            if (exoPlayer.playbackState == Player.STATE_IDLE) {
                                exoPlayer.prepare()
                            }
                            exoPlayer.play()
                        }
                        onReady()
                    }
                    return@withLock
                }

                val targetId = requestedId?.takeIf { it.isNotEmpty() } ?: userDataManager.lastPlayedItemId
                var item = if (targetId.isNotEmpty()) {
                    db.libraryItemDao().getLibraryItemById(targetId)
                } else {
                    null
                }
                if (item == null) {
                    item = db.libraryItemDao().getAllLibraryItems().firstOrNull()
                }
                if (item == null && targetId.isNotEmpty() && !userDataManager.offlineMode) {
                    item = ApiHandler(this@PlayerService).getItem(targetId, suppressToast = true)?.also {
                        db.libraryItemDao().insertLibraryItem(it)
                    }
                }

                item?.let { loadedItem ->
                    setAudiobook(loadedItem, loadedItem.userProgress.currentTime, autoPlay = autoPlay)
                    withContext(Dispatchers.Main) {
                        onReady()
                    }
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun setAudiobook(
        audiobook: LibraryItem,
        userTotalTime: Double,
        autoPlay: Boolean = true
    ) {
        if (audiobook.media.tracks.isEmpty()) {
            Timber.w("Cannot setAudiobook: tracks list is empty for ${audiobook.id}")
            return
        }

        // Determine target track index and seek position
        var totalDuration = 0.0
        var trackIndex = 0
        for (track in audiobook.media.tracks) {
            totalDuration += track.duration
            if (totalDuration > userTotalTime)
                break
            trackIndex++
        }
        trackIndex = trackIndex.coerceIn(0, audiobook.media.tracks.lastIndex)

        val userTrackTime = (userTotalTime - audiobook.media.tracks[trackIndex].startOffset).coerceAtLeast(0.0)
        val offsetSeconds = if (autoPlay) START_OFFSET_SECONDS else 0
        val seekMs = ((userTrackTime.toLong() - offsetSeconds).coerceAtLeast(0L)) * 1000L

        // Fast path: if the same audiobook is already loaded in ExoPlayer, just seek and play/pause
        val reusedExisting = withContext(Dispatchers.Main) {
            if (::exoPlayer.isInitialized &&
                this@PlayerService.audiobook.id == audiobook.id &&
                exoPlayer.mediaItemCount == audiobook.media.tracks.size &&
                exoPlayer.mediaItemCount > 0
            ) {
                this@PlayerService.audiobook = audiobook
                userDataManager.lastPlayedItemId = audiobook.id
                exoPlayer.seekTo(trackIndex, seekMs)
                if (exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
                setSpeed(userDataManager.speed)
                exoPlayer.playWhenReady = autoPlay
                if (autoPlay) {
                    exoPlayer.play()
                }
                updateUIMetadata()
                true
            } else {
                false
            }
        }
        if (reusedExisting) return

        // Prepare MediaSources on Dispatchers.IO so disk cache & download DB queries never block Main
        val sources = withContext(Dispatchers.IO) {
            val headers = hashMapOf<String, String>()
            headers["Authorization"] = "Bearer " + userDataManager.token
            val dataSourceFactory: DataSource.Factory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers)

            val downloadCache = MyDownloadService.getDownloadCache(this@PlayerService)

            val offlineCacheDataSourceFactory: DataSource.Factory =
                CacheDataSource.Factory()
                    .setCache(downloadCache)
                    .setUpstreamDataSourceFactory(null)
                    .setCacheWriteDataSinkFactory(null)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            val onlineCacheDataSourceFactory: DataSource.Factory =
                CacheDataSource.Factory()
                    .setCache(downloadCache)
                    .setUpstreamDataSourceFactory(dataSourceFactory)
                    .setCacheWriteDataSinkFactory(null)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            val builtSources = ArrayList<MediaSource>(audiobook.media.tracks.size)
            val baseAddress = userDataManager.getCompleteAddress()
            for (track in audiobook.media.tracks) {
                val url = baseAddress + track.contentUrl
                val mediaItem =
                    MediaItem.Builder()
                        .setMediaId("track-index-" + track.index)
                        .setUri(url)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setArtist(audiobook.media.metadata.authorName)
                                .setTitle(audiobook.media.metadata.title)
                                .build()
                        )
                        .build()

                val downloaded = track.isDownloaded(this@PlayerService)
                Timber.d("${track.index} downloaded = $downloaded")

                val factoryToUse = if (downloaded) offlineCacheDataSourceFactory else onlineCacheDataSourceFactory
                val mediaSource: MediaSource = ProgressiveMediaSource.Factory(factoryToUse)
                    .createMediaSource(mediaItem)
                builtSources.add(mediaSource)
            }
            builtSources
        }

        withContext(Dispatchers.Main) {
            this@PlayerService.audiobook = audiobook
            userDataManager.lastPlayedItemId = audiobook.id
            exoPlayer.run {
                clearMediaItems()
                setMediaSources(sources)
                seekTo(trackIndex, seekMs)
                prepare()
                setSpeed(userDataManager.speed)
                playWhenReady = autoPlay
                if (autoPlay) {
                    play()
                }
            }
            updateUIMetadata()
        }
    }


    fun getTotalPlaybackTime(): Long {
        return totalPlaybackTime
    }

    private fun seekRelativeMs(deltaMs: Long) {
        if (!::exoPlayer.isInitialized || exoPlayer.mediaItemCount == 0) return
        val newPos = exoPlayer.currentPosition + deltaMs
        if (newPos < 0 && exoPlayer.currentMediaItemIndex > 0) {
            val prevIndex = exoPlayer.currentMediaItemIndex - 1
            val prevDurationMs = (audiobook.media.tracks.getOrNull(prevIndex)?.duration?.times(1000)?.toLong()) ?: 0L
            exoPlayer.seekTo(prevIndex, (prevDurationMs + newPos).coerceAtLeast(0L))
        } else {
            exoPlayer.seekTo(newPos.coerceAtLeast(0L))
        }
        saveProgress()
        updateUIMetadata()
    }

    fun ensureLoadedIfNeeded(requestedId: String?) {
        ensureAudiobookLoaded(requestedId, autoPlay = false)
    }

    fun togglePlayPause(requestedId: String?) {
        if (!::exoPlayer.isInitialized || audiobook.id.isEmpty() || exoPlayer.mediaItemCount == 0) {
            ensureAudiobookLoaded(requestedId, autoPlay = true)
        } else if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            if (exoPlayer.playbackState == Player.STATE_IDLE) {
                exoPlayer.prepare()
            }
            exoPlayer.play()
        }
    }

    fun rewind(requestedId: String?) {
        ensureAudiobookLoaded(requestedId, autoPlay = false) {
            seekRelativeMs(-10_000L)
        }
    }

    fun fastForward(requestedId: String?) {
        ensureAudiobookLoaded(requestedId, autoPlay = false) {
            seekRelativeMs(10_000L)
        }
    }

    @OptIn(UnstableApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val requestedId = intent?.getStringExtra("id")

        when (intent?.action) {
            "ACTION_PLAY_PAUSE" -> {
                togglePlayPause(requestedId)
                return START_STICKY
            }

            "ACTION_REWIND" -> {
                rewind(requestedId)
                return START_STICKY
            }

            "ACTION_FAST_FORWARD" -> {
                fastForward(requestedId)
                return START_STICKY
            }
        }

        if (!requestedId.isNullOrEmpty()) {
            val requestedTime = intent.getDoubleExtra("time", -1.0)
            val action = intent.getStringExtra("action") ?: "default"
            scope.launch {
                loadMutex.withLock {
                    val item = db.libraryItemDao().getLibraryItemById(requestedId)
                        ?: if (!userDataManager.offlineMode) {
                            ApiHandler(this@PlayerService).getItem(requestedId, suppressToast = true)?.also {
                                db.libraryItemDao().insertLibraryItem(it)
                            }
                        } else null

                    item?.let {
                        val time = if (requestedTime < 0) it.userProgress.currentTime else requestedTime
                        val autoPlay = action != "preload"
                        setAudiobook(it, time, autoPlay = autoPlay)
                    }
                }
            }
        } else {
            ensureAudiobookLoaded(autoPlay = false)
        }

        return START_STICKY
    }

    fun getCurrentPosition(): Long {
        if (!::exoPlayer.isInitialized) return 0L
        return exoPlayer.currentPosition.coerceAtLeast(0L)
    }

    fun getDuration(): Long {
        if (!::exoPlayer.isInitialized) return 0L
        val dur = exoPlayer.duration
        return if (dur > 0) dur else 0L
    }


    override fun onDestroy() {
        stopPeriodicProgressSaving()
        networkConnectivityManager.stopMonitoring()
        
        // Final progress save before destruction
        if (::exoPlayer.isInitialized && audiobook.id.isNotEmpty()) { // Check if exoPlayer is initialized
            // Switch to main thread for ExoPlayer access if needed, or ensure saveProgress handles it
             if (exoPlayer.isPlaying || exoPlayer.playbackState != Player.STATE_IDLE) {
                saveProgress()
            }
        }
        
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        job.cancel()
        super.onDestroy()
    }

    fun getSpeed(): Float {
        return exoPlayer.playbackParameters.speed
    }

    fun setSpeed(speed: Float) {
        exoPlayer.setPlaybackSpeed(speed)
        userDataManager.speed = speed
    }

    companion object {
        fun setAudiobook(
            context: Context,
            item: LibraryItem,
            time: Double = -1.0,
            action: String = "default"
        ) {
            val serviceIntent = Intent(context, PlayerService::class.java).apply {
                putExtra(
                    "id",
                    item.id
                )
                putExtra(
                    "time",
                    time
                )
                putExtra(
                    "action",
                    action
                )
            }
            try {
                context.startService(serviceIntent)
            } catch (e: Exception) {
                context.startForegroundService(serviceIntent)
            }
        }
    }

}
