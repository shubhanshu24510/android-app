package com.github.ashutoshgngwr.noice.sound

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.github.ashutoshgngwr.noice.sound.player.Player
import com.google.gson.*
import com.google.gson.annotations.Expose
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type

// curious about the weird serialized names? see https://github.com/ashutoshgngwr/noice/issues/110
// and https://github.com/ashutoshgngwr/noice/pulls/117
/**
 * [Preset] is the serialization class to save player states onto the persistent storage using
 * Android's [SharedPreferences].
 */
class Preset private constructor(
  @Expose @SerializedName("a") var name: String,
  @Expose @SerializedName("b") val playerStates: Array<PlayerState>
) {

  /**
   * [PlayerState] can hold the state properties [key, volume, timePeriod] of a [Player] instance.
   * Used for JSON encoding.
   */
  data class PlayerState(
    @Expose @SerializedName("a") val soundKey: String,
    @Expose @SerializedName("b") @JsonAdapter(value = VolumeSerializer::class) val volume: Int,
    @Expose @SerializedName("c") val timePeriod: Int
  )

  /**
   * [VolumeSerializer] is a fix for maintaining backward compatibility with versions older than
   * 0.3.0. Volume was written as a Float to persistent storage in older versions.
   * Switching to Integer in newer version was causing crash if the user had any saved presets.
   */
  private inner class VolumeSerializer : JsonSerializer<Int>, JsonDeserializer<Int> {

    override fun serialize(src: Int, type: Type, ctx: JsonSerializationContext) =
      JsonPrimitive(src.toFloat() / Player.MAX_VOLUME)

    override fun deserialize(json: JsonElement, type: Type, ctx: JsonDeserializationContext) =
      (json.asFloat * Player.MAX_VOLUME).toInt()

  }

  companion object {
    /**
     * [from] exposes the primary constructor of [Preset] class. It automatically infers the
     * [PlayerState]s from provided [Collection] of [Player] instances.
     */
    fun from(name: String, players: Collection<Player>): Preset {
      val playerStates = arrayListOf<PlayerState>()
      for (player in players) {
        playerStates.add(PlayerState(player.soundKey, player.volume, player.timePeriod))
      }

      return Preset(name, playerStates.toTypedArray())
    }

    /**
     * convenience method.
     */
    private fun getPreferences(context: Context) =
      PreferenceManager.getDefaultSharedPreferences(context)

    /**
     * convenience method to add a fresh [Gson] instance to the context using a lambda.
     */
    private inline fun <T> withGson(crossinline f: (Gson) -> T) =
      // returns the value returned by the lambda. Just testing Kotlin :p
      GsonBuilder().excludeFieldsWithoutExposeAnnotation().create().let { f(it) }

    /**
     * [readAllFromUserPreferences] reads the serialized version of [Preset]s from the persistent
     * storage and returns an [ArrayList].
     */
    fun readAllFromUserPreferences(context: Context): Array<Preset> = withGson {
      it.fromJson(
        getPreferences(context).getString("presets", "[]"),
        Array<Preset>::class.java
      )
    }

    /**
     * [writeAllToUserPreferences] will overwrite the current collection of [Preset]s by the
     * given [ArrayList] in the persistent storage.
     */
    fun writeAllToUserPreferences(context: Context, presets: Collection<Preset>) {
      withGson {
        getPreferences(context).also { prefs ->
          prefs.edit()
            .putString("presets", it.toJson(presets))
            .apply()
        }
      }
    }

    /**
     * [appendToUserPreferences] appends the given [Preset] to the collection of [Preset]s already
     * present in the persistent storage.
     */
    fun appendToUserPreferences(context: Context, preset: Preset) {
      readAllFromUserPreferences(context).also {
        writeAllToUserPreferences(context, listOf(*it, preset))
      }
    }
  }

  init {
    // for stable equality operations
    playerStates.sortBy { T -> T.soundKey }
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) {
      return true
    }

    if (other !is Preset) {
      return false
    }

    // name need not be equal. playbackStates should be
    return playerStates.contentEquals(other.playerStates)
  }

  override fun hashCode(): Int {
    // auto-generated
    var result = name.hashCode()
    result = 31 * result + playerStates.contentHashCode()
    return result
  }
}
