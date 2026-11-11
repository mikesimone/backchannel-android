package net.mikesimone.backchannel.immich

import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Backchannel, Mike 2026-11-11: single sign-on in the phone's browser, the
 * same way Immich's own phone app does it, so it works wherever that app's
 * sign-in works and with providers that refuse sign-in inside an app's
 * WebView (Google: "disallowed_useragent").
 *
 * Immich's flow (server auth.service / mobile oauth.service):
 *  1. POST /api/oauth/authorize {redirectUri: "app.immich:///oauth-callback", state, codeChallenge}
 *     answers {url}: the provider's sign-in page. A server with "mobile redirect
 *     URI override" swaps the app.immich address for its /api/oauth/mobile-redirect
 *     page, which bounces the browser on to app.immich:///oauth-callback?code=...&state=...
 *  2. The browser opens that app.immich address; Backchannel answers it
 *     ([ImmichOAuthCallbackActivity]).
 *  3. POST /api/oauth/callback {url: <that app.immich address>, state, codeVerifier}
 *     answers with accessToken.
 *
 * This file is the pure part (PKCE, parsing the return address, the state
 * check), free of Android classes so it can be unit tested.
 */
object ImmichBrowserSso {

  const val SCHEME = "app.immich"
  const val REDIRECT_URI = "app.immich:///oauth-callback"

  /** A pending sign-in lasts this long. */
  const val MAX_AGE_MS = 15 * 60 * 1000L

  private val random = SecureRandom()

  private fun randomString(bytes: Int): String {
    val b = ByteArray(bytes)
    random.nextBytes(b)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
  }

  fun newState(): String = randomString(24)

  /** RFC 7636 code verifier: 43 characters from the unreserved set. */
  fun newCodeVerifier(): String = randomString(32)

  /** RFC 7636 S256 code challenge. */
  fun codeChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
  }

  /** True for Immich's app return address, `app.immich:///oauth-callback?...` (also the one-slash form some browsers produce). */
  fun isCallback(url: String?): Boolean {
    if (url == null) return false
    val lower = url.lowercase()
    if (!lower.startsWith("$SCHEME:")) return false
    val rest = lower.removePrefix("$SCHEME:").trimStart('/')
    return rest.substringBefore('?').trimEnd('/') == "oauth-callback"
  }

  /** The return address in the form Immich's server expects (`app.immich:///oauth-callback?...`). */
  fun normalizeCallback(url: String): String {
    val query = url.substringAfter('?', "")
    return if (query.isEmpty()) REDIRECT_URI else "$REDIRECT_URI?$query"
  }

  /** A query parameter of a URL of any scheme, decoded. */
  fun queryParameter(url: String, name: String): String? {
    val query = url.substringAfter('?', "").substringBefore('#')
    if (query.isEmpty()) return null
    for (pair in query.split('&')) {
      val key = decode(pair.substringBefore('='))
      if (key == name) return decode(pair.substringAfter('=', ""))
    }
    return null
  }

  private fun decode(s: String): String = try {
    URLDecoder.decode(s, "UTF-8")
  } catch (e: IllegalArgumentException) {
    s
  }

  /**
   * The state the provider will send back. Newer Immich passes our state on;
   * older servers ignore it and make their own, which then shows in the
   * provider address they return.
   */
  fun expectedState(authorizeUrl: String, ours: String): String = queryParameter(authorizeUrl, "state")?.takeIf { it.isNotEmpty() } ?: ours

  /** Accept a return only for the sign-in in progress: same state, a code, and not too old. */
  fun accepts(callbackUrl: String, expectedState: String?, startedAt: Long, now: Long): Boolean {
    if (expectedState.isNullOrEmpty()) return false
    if (now - startedAt !in 0..MAX_AGE_MS) return false
    if (!isCallback(callbackUrl)) return false
    if (queryParameter(callbackUrl, "code").isNullOrEmpty()) return false
    return queryParameter(callbackUrl, "state") == expectedState
  }

  /** True if a WebView page is Google's sign-in, which won't work inside an app. */
  fun isGoogleSignIn(url: String?): Boolean {
    if (url == null) return false
    val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore(':').lowercase()
    return host == "accounts.google.com" || url.contains("disallowed_useragent")
  }
}
