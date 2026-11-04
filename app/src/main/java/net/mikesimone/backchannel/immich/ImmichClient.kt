package net.mikesimone.backchannel.immich

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.annotation.WorkerThread
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.thoughtcrime.securesms.dependencies.AppDependencies
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Backchannel (Mike 2026-11-04): talks to the user's own Immich server, so
 * Immich users can attach photos without Google Photos. Android's photo picker
 * only offers cloud apps the phone maker signed up, and Immich can't act as a
 * picker for other apps, so Backchannel reads the library itself.
 *
 * Signs in with an Immich API key (works with password and OAuth accounts).
 * The server address and key are stored on this phone only, in app-private
 * preferences. Uses the deprecated-but-supported page-based search so it works
 * with Immich 1.x through 3.x.
 */
object ImmichClient {

  private const val PREFS = "backchannel_immich"
  private const val KEY_SERVER = "server"
  private const val KEY_API_KEY = "api_key"
  private const val PAGE_SIZE = 120

  /** Formats Signal can send as-is. Others (HEIC, RAW...) go out as Immich's JPEG preview. */
  private val SENDABLE_IMAGES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

  data class Asset(val id: String, val isVideo: Boolean, val mimeType: String, val duration: String?)

  data class Album(val id: String, val name: String, val count: Int, val thumbnailId: String?)

  data class Page(val assets: List<Asset>, val nextPage: Int?)

  class ImmichException(message: String, val code: Int = 0) : IOException(message)

  private val client: OkHttpClient by lazy {
    AppDependencies.okHttpClient.newBuilder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS)
      .build()
  }

  private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun isConnected(context: Context): Boolean = server(context) != null && apiKey(context) != null

  fun server(context: Context): String? = prefs(context).getString(KEY_SERVER, null)

  private fun apiKey(context: Context): String? = prefs(context).getString(KEY_API_KEY, null)

  fun disconnect(context: Context) {
    prefs(context).edit().clear().apply()
  }

  /** "photos.example.com", "https://photos.example.com/" or ".../api" all become "https://photos.example.com". */
  fun normalizeServer(input: String): String? {
    var s = input.trim().trimEnd('/')
    if (s.isEmpty()) return null
    if (!s.contains("://")) s = "https://$s"
    if (s.endsWith("/api")) s = s.removeSuffix("/api")
    return s.toHttpUrlOrNull()?.toString()?.trimEnd('/')
  }

  /** Checks the address and key, and saves them if they work. Returns the account name. */
  @WorkerThread
  fun connect(context: Context, serverInput: String, key: String): String {
    val server = normalizeServer(serverInput) ?: throw ImmichException("That server address doesn't look right")
    val apiKey = key.trim().ifEmpty { throw ImmichException("Paste an API key") }
    val me = JSONObject(get(server, apiKey, "users/me"))
    prefs(context).edit().putString(KEY_SERVER, server).putString(KEY_API_KEY, apiKey).apply()
    return me.optString("name").ifBlank { me.optString("email") }
  }

  /** Newest first. [albumId] limits it to one album; [query] uses Immich's smart search. */
  @WorkerThread
  fun assets(context: Context, page: Int, albumId: String? = null, query: String? = null): Page {
    val body = JSONObject().put("page", page).put("size", PAGE_SIZE)
    val path = if (!query.isNullOrBlank()) {
      body.put("query", query.trim())
      "search/smart"
    } else {
      body.put("order", "desc").put("withDeleted", false)
      if (albumId != null) body.put("albumIds", JSONArray().put(albumId))
      "search/metadata"
    }
    val assets = JSONObject(post(context, path, body)).getJSONObject("assets")
    val items = assets.getJSONArray("items")
    val list = (0 until items.length()).mapNotNull { i ->
      val a = items.getJSONObject(i)
      val type = a.optString("type")
      if (type != "IMAGE" && type != "VIDEO") return@mapNotNull null
      if (a.optBoolean("isTrashed")) return@mapNotNull null
      Asset(
        id = a.getString("id"),
        isVideo = type == "VIDEO",
        mimeType = a.optString("originalMimeType").ifBlank { if (type == "VIDEO") "video/mp4" else "image/jpeg" },
        duration = a.optString("duration").takeIf { type == "VIDEO" }?.let(::shortDuration)
      )
    }
    val next = if (assets.isNull("nextPage")) null else assets.optString("nextPage").toIntOrNull()
    return Page(list, next)
  }

  @WorkerThread
  fun albums(context: Context): List<Album> {
    // GET /albums lists owned and shared-with-me albums together.
    val arr = JSONArray(get(context, "albums"))
    val all = (0 until arr.length()).map { i ->
      val a = arr.getJSONObject(i)
      Album(a.getString("id"), a.optString("albumName"), a.optInt("assetCount"), a.optString("albumThumbnailAssetId").takeIf { it.isNotBlank() && it != "null" })
    }
    return all.filter { it.count > 0 }
  }

  @WorkerThread
  fun thumbnail(context: Context, assetId: String): ByteArray = bytes(context, "assets/$assetId/thumbnail?size=thumbnail")

  /** Downloads [asset] into [dir] in a form Signal can send, and returns the file. */
  @WorkerThread
  fun download(context: Context, asset: Asset, dir: File): File {
    val usePreview = !asset.isVideo && asset.mimeType.lowercase() !in SENDABLE_IMAGES
    val path = if (usePreview) "assets/${asset.id}/thumbnail?size=preview" else "assets/${asset.id}/original"
    val request = request(context, path).get().build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw ImmichException("Immich said ${response.code}", response.code)
      val body = response.body
      val mime = (if (usePreview) response.header("Content-Type") else asset.mimeType)?.substringBefore(';')?.trim() ?: "image/jpeg"
      val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: if (asset.isVideo) "mp4" else "jpg"
      val file = File(dir, "${asset.id}.$ext")
      file.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
      return file
    }
  }

  private fun shortDuration(d: String): String? {
    // Immich gives "0:01:23.456000"
    val parts = d.substringBefore('.').split(':').mapNotNull { it.toIntOrNull() }
    if (parts.size != 3) return null
    val (h, m, s) = parts
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
  }

  private fun url(server: String, path: String): HttpUrl {
    return "$server/api/$path".toHttpUrlOrNull() ?: throw ImmichException("Bad server address")
  }

  private fun request(context: Context, path: String): Request.Builder {
    val server = server(context) ?: throw ImmichException("Not connected to Immich")
    val key = apiKey(context) ?: throw ImmichException("Not connected to Immich")
    return Request.Builder().url(url(server, path)).header("x-api-key", key)
  }

  private fun get(server: String, key: String, path: String): String {
    val request = Request.Builder().url(url(server, path)).header("x-api-key", key).header("Accept", "application/json").get().build()
    return execute(request)
  }

  private fun get(context: Context, path: String): String = execute(request(context, path).header("Accept", "application/json").get().build())

  private fun post(context: Context, path: String, body: JSONObject): String {
    val request = request(context, path)
      .header("Accept", "application/json")
      .post(body.toString().toRequestBody("application/json".toMediaType()))
      .build()
    return execute(request)
  }

  private fun bytes(context: Context, path: String): ByteArray {
    client.newCall(request(context, path).get().build()).execute().use { response ->
      if (!response.isSuccessful) throw ImmichException("Immich said ${response.code}", response.code)
      return response.body.bytes()
    }
  }

  private fun execute(request: Request): String {
    client.newCall(request).execute().use { response ->
      val text = response.body.string()
      if (!response.isSuccessful) {
        val message = when (response.code) {
          401 -> "Immich didn't accept that API key"
          403 -> "That API key is missing a permission (needs asset and album read, view and download)"
          404 -> "No Immich server found at that address"
          else -> "Immich said ${response.code}"
        }
        throw ImmichException(message, response.code)
      }
      return text
    }
  }
}
