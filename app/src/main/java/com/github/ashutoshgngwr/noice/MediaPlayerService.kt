package com.github.ashutoshgngwr.noice

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.content.getSystemService
import androidx.mediarouter.media.MediaRouter
import com.github.ashutoshgngwr.noice.activity.MainActivity
import com.github.ashutoshgngwr.noice.playback.PlaybackController
import com.github.ashutoshgngwr.noice.playback.Player
import com.github.ashutoshgngwr.noice.playback.PlayerManager
import com.github.ashutoshgngwr.noice.playback.PlayerNotificationManager
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.TimeUnit

class MediaPlayerService : Service() {
  /**
   * [MediaPlayerService] publishes [PlaybackUpdateEvent] whenever there's an update to
   * [PlayerManager].
   *
   * @param state [PlaybackStateCompat] representing the current player state
   * @param players a snapshot of currently playing sound [Players][Player]
   */
  data class PlaybackUpdateEvent(val state: Int, val players: Map<String, Player>)

  companion object {
    private val TAG = MediaPlayerService::class.java.simpleName
    private val WAKELOCK_TIMEOUT = TimeUnit.DAYS.toMillis(1)

    private const val FOREGROUND_ID = 0x29
    private const val RC_MAIN_ACTIVITY = 0x28
  }

  private lateinit var wakeLock: PowerManager.WakeLock
  private lateinit var mediaSession: MediaSessionCompat
  private lateinit var playerManager: PlayerManager

  private val handler = Handler(Looper.getMainLooper())

  private val becomingNoisyReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
        Log.i(TAG, "Becoming noisy... Pause playback!")
        playerManager.pause()
      }
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    wakeLock = requireNotNull(getSystemService<PowerManager>()).run {
      newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, packageName).apply {
        setReferenceCounted(false)
      }
    }

    mediaSession = MediaSessionCompat(this, "$TAG.mediaSession").also {
      it.setMetadata(
        MediaMetadataCompat.Builder()
          .putString(MediaMetadataCompat.METADATA_KEY_TITLE, getString(R.string.app_name))
          .build()
      )

      it.setCallback(object : MediaSessionCompat.Callback() {
        override fun onPlay() = playerManager.resume()
        override fun onStop() = playerManager.stop()
        override fun onPause() = playerManager.pause()
      })

      it.setSessionActivity(
        PendingIntent.getActivity(
          this,
          RC_MAIN_ACTIVITY,
          Intent(this, MainActivity::class.java),
          PendingIntent.FLAG_UPDATE_CURRENT
        )
      )

      it.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
      it.isActive = true
      MediaRouter.getInstance(this).setMediaSessionCompat(it)
    }

    playerManager = PlayerManager(this, mediaSession)
    playerManager.setPlaybackUpdateListener(this::onPlaybackUpdate)

    PlayerNotificationManager.createChannel(this)
    registerReceiver(becomingNoisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
  }

  private fun onPlaybackUpdate(state: Int, players: Map<String, Player>) {
    EventBus.getDefault().postSticky(PlaybackUpdateEvent(state, players))

    if (state == PlaybackStateCompat.STATE_STOPPED) {
      stopForeground(true)
      wakeLock.release()
    } else {
      wakeLock.acquire(WAKELOCK_TIMEOUT)
      startForeground(
        FOREGROUND_ID,
        PlayerNotificationManager.createNotification(this, mediaSession)
      )
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    intent?.also { PlaybackController.handleServiceIntent(this, playerManager, it, handler) }
    return START_STICKY
  }

  override fun onDestroy() {
    unregisterReceiver(becomingNoisyReceiver)
    playerManager.cleanup()
    mediaSession.release()
    PlaybackController.clearAutoStopCallback(handler)
    wakeLock.release()
    super.onDestroy()
  }
}
