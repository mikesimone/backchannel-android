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
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.dependencies.AppDependencies
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Backchannel (Mike 2026-11-04): talks to the user's own Immich server, so
 * Immich users can attach photos without Google Photos. Android's photo picker
 * only offers cloud apps the phone maker signed up, so Backchannel reads the
 * library itself.
 *
 * Backchannel, Mike 2026-11-11: signs in the way the server offers. The usual
 * path is the server's own web login page (password or any OAuth/SSO provider,
 * see [ImmichLoginActivity]), which gives a session token sent as
 * `Authorization: Bearer`. An API key (`x-api-key`) still works as an advanced
 * option. An optional "address at home" is used automatically when it answers
 * (see [ImmichRouter]). Both addresses must be https (Mike 2026-11-11: no
 * app-wide cleartext allowance). Addresses and the token or key stay on this phone, in
 * app-private preferences. For providers that block sign-in inside apps
 * (Google), [ImmichBrowserSso] signs in through the browser instead, the way
 * Immich's own phone app does. Uses the deprecated-but-supported page-based search
 * so it works with Immich 1.x through 3.x.
 */
object ImmichClient {

  private val TAG = Log.tag(ImmichClient::class.java)

  private const val PREFS = "backchannel_immich"
  private const val SSO_PREFS = "backchannel_immich_sso"
  private const val KEY_SERVER = "server"
  private const val KEY_HOME = "home"
  private const val KEY_API_KEY = "api_key"
  private const val KEY_SESSION = "session_token"
  private const val PAGE_SIZE = 120

  /** Formats Signal can send as-is. Others (HEIC, RAW...) go out as Immich's JPEG preview. */
  private val SENDABLE_IMAGES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

  enum class Auth { SESSION, API_KEY }

  data class Asset(val id: String, val isVideo: Boolean, val mimeType: String, val duration: String?)

  /** [lastAdded] is when a photo was last added (or the album last changed); [newestPhoto] is the date of its newest photo. ISO 8601 UTC, or "". */
  data class Album(val id: String, val name: String, val count: Int, val thumbnailId: String?, val lastAdded: String = "", val newestPhoto: String = "")

  data class Page(val assets: List<Asset>, val nextPage: Int?)

  class ImmichException(message: String, val code: Int = 0) : IOException(message) {
    val unauthorized: Boolean get() = code == 401
  }

  /** The two addresses after checking what the user typed. */
  data class Addresses(val main: String, val home: String?)


  private val client: OkHttpClient by lazy {
    AppDependencies.okHttpClient.newBuilder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS)
      .addInterceptor { chain ->
        // Immich traffic is https only (Mike 2026-11-11).
        val request = chain.request()
        if (!request.url.isHttps) throw IOException("Immich addresses must use https")
        chain.proceed(request)
      }
      .build()
  }

  private val pingClient: OkHttpClient by lazy {
    client.newBuilder()
      .connectTimeout(1, TimeUnit.SECONDS)
      .readTimeout(1, TimeUnit.SECONDS)
      .callTimeout(2, TimeUnit.SECONDS)
      .retryOnConnectionFailure(false)
      .build()
  }

  private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun isConnected(context: Context): Boolean = server(context) != null && auth(context) != null

  fun server(context: Context): String? = prefs(context).getString(KEY_SERVER, null)

  fun home(context: Context): String? = prefs(context).getString(KEY_HOME, null)

  fun auth(context: Context): Auth? {
    val p = prefs(context)
    return when {
      p.getString(KEY_SESSION, null) != null -> Auth.SESSION
      p.getString(KEY_API_KEY, null) != null -> Auth.API_KEY
      else -> null
    }
  }

  /**
   * Signs out: ends the server session (if it was a sign-in, best effort) and
   * forgets the token or key and both addresses.
   */
  @WorkerThread
  fun signOut(context: Context) {
    val p = prefs(context)
    val server = p.getString(KEY_SERVER, null)
    val token = p.getString(KEY_SESSION, null)
    if (server != null && token != null) {
      try {
        val request = Request.Builder().url(url(server, "auth/logout")).header("Authorization", "Bearer $token")
          .post("{}".toRequestBody("application/json".toMediaType())).build()
        pingClient.newBuilder().callTimeout(5, TimeUnit.SECONDS).build().newCall(request).execute().close()
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't end the Immich session", e)
      }
    }
    p.edit().clear().apply()
    clearSso(context)
    ImmichRouter.invalidate()
  }

  /** Forgets only the token or key, keeping the addresses for signing in again. */
  fun forgetCredentials(context: Context) {
    prefs(context).edit().remove(KEY_SESSION).remove(KEY_API_KEY).apply()
  }

  /**
   * Checks what the user typed. Both addresses must be https (Mike 2026-11-11);
   * without a scheme, https is assumed.
   */
  fun addresses(context: Context, serverInput: String, homeInput: String): Addresses {
    val main = ImmichUrls.normalize(serverInput) ?: throw ImmichException(context.getString(R.string.Immich__bad_address))
    if (!ImmichUrls.isHttps(main)) throw ImmichException(context.getString(R.string.Immich__needs_https))
    val home = if (homeInput.isBlank()) {
      null
    } else {
      ImmichUrls.normalize(homeInput) ?: throw ImmichException(context.getString(R.string.Immich__bad_home_address))
    }
    if (home != null && !ImmichUrls.isHttps(home)) throw ImmichException(context.getString(R.string.Immich__home_needs_https))
    return Addresses(main, home?.takeIf { it != main })
  }

  /** Checks the address and API key, and saves them if they work. Returns the account name. */
  @WorkerThread
  fun connectWithKey(context: Context, addresses: Addresses, key: String): String {
    val apiKey = key.trim().ifEmpty { throw ImmichException(context.getString(R.string.Immich__paste_key)) }
    val me = me(addresses.main, "x-api-key", apiKey, Auth.API_KEY)
    save(context, addresses, KEY_API_KEY, apiKey)
    return me
  }

  /** Checks a session token from signing in, and saves it if it works. Returns the account name. */
  @WorkerThread
  fun connectWithSession(context: Context, addresses: Addresses, token: String): String {
    val me = me(addresses.main, "Authorization", "Bearer $token", Auth.SESSION)
    save(context, addresses, KEY_SESSION, token)
    return me
  }

  private fun save(context: Context, addresses: Addresses, key: String, value: String) {
    val editor = prefs(context).edit().clear().putString(KEY_SERVER, addresses.main).putString(key, value)
    if (addresses.home != null) editor.putString(KEY_HOME, addresses.home)
    editor.apply()
    ImmichRouter.invalidate()
  }

  /** GET /users/me, or /user/me on old servers. */
  private fun me(server: String, header: String, value: String, auth: Auth): String {
    fun get(path: String): String {
      val request = Request.Builder().url(url(server, path)).header(header, value).header("Accept", "application/json").get().build()
      return client.newCall(request).execute().use { text(it, auth) }
    }
    val json = try {
      get("users/me")
    } catch (e: ImmichException) {
      if (e.code == 404) get("user/me") else throw e
    }
    val me = JSONObject(json)
    return me.optString("name").ifBlank { me.optString("email") }
  }

  /**
   * Finishes an OAuth sign-in the WebView was sent back from: the full
   * `<server>/auth/login?code=...&state=...` URL goes to POST /oauth/callback,
   * with the WebView's cookies for the server (newer Immich keeps the OAuth
   * state and PKCE verifier there). Returns the session token.
   */
  @WorkerThread
  fun finishOAuth(server: String, returnUrl: String, cookies: String?): String {
    val request = Request.Builder().url(url(server, "oauth/callback"))
      .header("Accept", "application/json")
      .apply { if (!cookies.isNullOrBlank()) header("Cookie", cookies) }
      .post(JSONObject().put("url", returnUrl).toString().toRequestBody("application/json".toMediaType()))
      .build()
    val json = JSONObject(client.newCall(request).execute().use { text(it, Auth.SESSION) })
    return json.optString("accessToken").ifBlank { throw ImmichException("Immich didn't send a sign-in token") }
  }

  /** A browser sign-in in progress ([ImmichBrowserSso]). [callbackUrl] is set once the browser came back. */
  data class PendingSso(val addresses: Addresses, val state: String, val codeVerifier: String, val startedAt: Long, val callbackUrl: String?)

  private fun ssoPrefs(context: Context) = context.applicationContext.getSharedPreferences(SSO_PREFS, Context.MODE_PRIVATE)

  /**
   * Starts a browser sign-in like Immich's phone app does and remembers it
   * (in app-private preferences, so it survives the app being closed while
   * the browser is open). Returns the provider address to open.
   */
  @WorkerThread
  fun startBrowserSso(context: Context, addresses: Addresses): String {
    val state = ImmichBrowserSso.newState()
    val verifier = ImmichBrowserSso.newCodeVerifier()
    val body = JSONObject()
      .put("redirectUri", ImmichBrowserSso.REDIRECT_URI)
      .put("state", state)
      .put("codeChallenge", ImmichBrowserSso.codeChallenge(verifier))
    val request = Request.Builder().url(url(addresses.main, "oauth/authorize"))
      .header("Accept", "application/json")
      .post(body.toString().toRequestBody("application/json".toMediaType()))
      .build()
    val response = client.newCall(request).execute().use { response ->
      val text = response.body.string()
      if (response.code == 400) throw ImmichException(context.getString(R.string.Immich__sso_not_enabled), 400)
      if (!response.isSuccessful) throw error(response.code, Auth.SESSION)
      JSONObject(text)
    }
    val providerUrl = response.optString("url").ifBlank { throw ImmichException(context.getString(R.string.Immich__sso_not_enabled)) }
    ssoPrefs(context).edit().clear()
      .putString("main", addresses.main)
      .putString("home", addresses.home)
      .putString("state", ImmichBrowserSso.expectedState(providerUrl, state))
      .putString("verifier", verifier)
      .putLong("started", System.currentTimeMillis())
      .apply()
    return providerUrl
  }

  fun pendingSso(context: Context): PendingSso? {
    val p = ssoPrefs(context)
    val main = p.getString("main", null) ?: return null
    val state = p.getString("state", null) ?: return null
    val verifier = p.getString("verifier", null) ?: return null
    val started = p.getLong("started", 0)
    if (System.currentTimeMillis() - started !in 0..ImmichBrowserSso.MAX_AGE_MS) {
      clearSso(context)
      return null
    }
    return PendingSso(Addresses(main, p.getString("home", null)), state, verifier, started, p.getString("callback", null))
  }

  /** The browser came back with [url]. Kept only if it belongs to the sign-in in progress. */
  fun deliverSsoCallback(context: Context, url: String): Boolean {
    val pending = pendingSso(context) ?: return false
    if (pending.callbackUrl != null) return false
    if (!ImmichBrowserSso.accepts(url, pending.state, pending.startedAt, System.currentTimeMillis())) return false
    ssoPrefs(context).edit().putString("callback", ImmichBrowserSso.normalizeCallback(url)).commit()
    return true
  }

  fun clearSso(context: Context) {
    ssoPrefs(context).edit().clear().apply()
  }

  /** Finishes a browser sign-in that came back: token from POST /oauth/callback, then saved like any sign-in. */
  @WorkerThread
  fun finishBrowserSso(context: Context, pending: PendingSso): String {
    val callbackUrl = pending.callbackUrl ?: throw ImmichException(context.getString(R.string.Immich__sign_in_failed))
    clearSso(context)
    val body = JSONObject().put("url", callbackUrl).put("state", pending.state).put("codeVerifier", pending.codeVerifier)
    val request = Request.Builder().url(url(pending.addresses.main, "oauth/callback"))
      .header("Accept", "application/json")
      .post(body.toString().toRequestBody("application/json".toMediaType()))
      .build()
    val json = JSONObject(client.newCall(request).execute().use { text(it, Auth.SESSION) })
    val token = json.optString("accessToken").ifBlank { throw ImmichException("Immich didn't send a sign-in token") }
    return connectWithSession(context, pending.addresses, token)
  }

  /** Probes the home address now (called as an Immich screen opens). */
  @WorkerThread
  fun refreshRoute(context: Context) {
    val main = server(context) ?: return
    val home = home(context) ?: return
    ImmichRouter.invalidate()
    ImmichRouter.base(context, main, home, ::ping)
  }

  /** True if [server] answers Immich's ping: /server/ping, or /server-info/ping on old versions. */
  @WorkerThread
  fun ping(server: String): Boolean {
    for (path in listOf("server/ping", "server-info/ping")) {
      try {
        val request = Request.Builder().url(url(server, path)).header("Accept", "application/json").get().build()
        pingClient.newCall(request).execute().use { response ->
          if (response.isSuccessful) return ImmichUrls.isPong(response.body.string())
          if (response.code != 404) return false
        }
      } catch (e: IOException) {
        return false
      }
    }
    return false
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

  private fun iso(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key)

  @WorkerThread
  fun albums(context: Context): List<Album> {
    // GET /albums lists owned and shared-with-me albums together.
    val arr = JSONArray(get(context, "albums"))
    val all = (0 until arr.length()).map { i ->
      val a = arr.getJSONObject(i)
      Album(
        a.getString("id"),
        a.optString("albumName"),
        a.optInt("assetCount"),
        a.optString("albumThumbnailAssetId").takeIf { it.isNotBlank() && it != "null" },
        lastAdded = iso(a, "lastModifiedAssetTimestamp").ifEmpty { iso(a, "updatedAt") },
        newestPhoto = iso(a, "endDate")
      )
    }
    return all.filter { it.count > 0 }
  }

  @WorkerThread
  fun thumbnail(context: Context, assetId: String): ByteArray {
    call(context, "assets/$assetId/thumbnail?size=thumbnail") { it.get() }.use { response ->
      if (!response.isSuccessful) throw error(response.code, auth(context))
      return response.body.bytes()
    }
  }

  /** Downloads [asset] into [dir] in a form Signal can send, and returns the file. */
  @WorkerThread
  fun download(context: Context, asset: Asset, dir: File): File {
    val usePreview = !asset.isVideo && asset.mimeType.lowercase() !in SENDABLE_IMAGES
    val path = if (usePreview) "assets/${asset.id}/thumbnail?size=preview" else "assets/${asset.id}/original"
    call(context, path) { it.get() }.use { response ->
      if (!response.isSuccessful) throw error(response.code, auth(context))
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

  /**
   * Runs a request against the address [ImmichRouter] picks. If the home
   * address fails to connect, it switches to the main address (until the next
   * network change) and tries once more there.
   */
  private fun call(context: Context, path: String, method: (Request.Builder) -> Request.Builder): Response {
    val main = server(context) ?: throw ImmichException("Not connected to Immich")
    val home = home(context)
    val base = ImmichRouter.base(context, main, home, ::ping)
    try {
      return client.newCall(method(request(context, base, path)).build()).execute()
    } catch (e: IOException) {
      if (home == null || base != home) throw e
      Log.w(TAG, "Home address failed, using the main address", e)
      ImmichRouter.useMain(main, home)
      return client.newCall(method(request(context, main, path)).build()).execute()
    }
  }

  private fun request(context: Context, base: String, path: String): Request.Builder {
    val p = prefs(context)
    val builder = Request.Builder().url(url(base, path))
    val token = p.getString(KEY_SESSION, null)
    val key = p.getString(KEY_API_KEY, null)
    return when {
      token != null -> builder.header("Authorization", "Bearer $token")
      key != null -> builder.header("x-api-key", key)
      else -> throw ImmichException("Not connected to Immich", 401)
    }
  }

  private fun get(context: Context, path: String): String {
    return call(context, path) { it.header("Accept", "application/json").get() }.use { text(it, auth(context)) }
  }

  private fun post(context: Context, path: String, body: JSONObject): String {
    return call(context, path) { it.header("Accept", "application/json").post(body.toString().toRequestBody("application/json".toMediaType())) }
      .use { text(it, auth(context)) }
  }

  private fun text(response: Response, auth: Auth?): String {
    val text = response.body.string()
    if (!response.isSuccessful) throw error(response.code, auth)
    return text
  }

  private fun error(code: Int, auth: Auth?): ImmichException {
    val message = when (code) {
      401 -> if (auth == Auth.API_KEY) "Immich didn't accept that API key" else "Signed out of Immich"
      403 -> if (auth == Auth.API_KEY) "That API key is missing a permission (needs asset and album read, view and download)" else "Immich refused that (403)"
      404 -> "No Immich server found at that address"
      else -> "Immich said $code"
    }
    return ImmichException(message, code)
  }
}
