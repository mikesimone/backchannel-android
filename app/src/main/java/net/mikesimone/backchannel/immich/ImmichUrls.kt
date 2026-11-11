package net.mikesimone.backchannel.immich

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Backchannel, Mike 2026-11-11: the address and sign-in rules for Immich, kept
 * free of Android classes so they can be unit tested.
 */
object ImmichUrls {

  const val ACCESS_TOKEN_COOKIE = "immich_access_token"

  /**
   * Turns whatever the user typed or pasted into a server origin
   * ("scheme://host[:port]"), or null if it isn't an address. Immich runs at
   * the root of its host, so anything after the host ("/api", "/photos",
   * "/auth/login") is dropped. Without a scheme it uses [defaultScheme].
   */
  fun normalize(input: String, defaultScheme: String = "https"): String? {
    val s = input.trim()
    if (s.isEmpty() || s.any { it.isWhitespace() }) return null
    val withScheme = if (s.contains("://")) s else "$defaultScheme://$s"
    val url = withScheme.toHttpUrlOrNull() ?: return null
    if (url.host.isEmpty()) return null
    return origin(url)
  }

  fun isHttps(server: String): Boolean = server.toHttpUrlOrNull()?.isHttps == true

  fun origin(url: HttpUrl): String {
    val defaultPort = HttpUrl.defaultPort(url.scheme)
    val host = if (url.host.contains(':')) "[${url.host}]" else url.host
    return if (url.port == defaultPort) "${url.scheme}://$host" else "${url.scheme}://$host:${url.port}"
  }

  fun sameOrigin(a: String, b: String): Boolean {
    val ua = a.toHttpUrlOrNull() ?: return false
    val ub = b.toHttpUrlOrNull() ?: return false
    return ua.scheme == ub.scheme && ua.host.equals(ub.host, ignoreCase = true) && ua.port == ub.port
  }

  /** The web login page on [server]. */
  fun loginPage(server: String): String = "$server/auth/login"

  /**
   * True when the WebView is being sent back from an identity provider to
   * Immich's web login page with an authorization code, i.e.
   * `<server>/auth/login?code=...&state=...`. An `error=` return is left to the
   * web page, which shows the provider's message.
   */
  fun isOAuthReturn(server: String, url: String): Boolean {
    val u = url.toHttpUrlOrNull() ?: return false
    if (!sameOrigin(server, url)) return false
    if (!u.encodedPath.trimEnd('/').endsWith("/auth/login")) return false
    return !u.queryParameter("code").isNullOrBlank()
  }

  /** The value of [name] in a `Cookie:` style header ("a=1; b=2"), or null. */
  fun cookie(header: String?, name: String): String? {
    if (header.isNullOrBlank()) return null
    return header.split(';')
      .map { it.trim() }
      .firstOrNull { it.substringBefore('=').trim() == name }
      ?.substringAfter('=', "")
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
  }

  /** Every cookie name in a `Cookie:` style header. */
  fun cookieNames(header: String?): List<String> {
    if (header.isNullOrBlank()) return emptyList()
    return header.split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
  }

  private val PONG = Regex("\"res\"\\s*:\\s*\"pong\"")

  /** Immich's ping answers `{"res":"pong"}`. */
  fun isPong(body: String?): Boolean = body != null && PONG.containsMatchIn(body)

  /** Which address to use: home only when it's set and answered the ping. */
  fun choose(main: String, home: String?, homeAnswered: Boolean): String = if (home != null && homeAnswered) home else main

  /** Cleartext (http) is allowed only to the user's own home address. */
  fun cleartextAllowed(url: HttpUrl, home: String?): Boolean {
    if (url.isHttps) return true
    val h = home?.toHttpUrlOrNull() ?: return false
    return h.scheme == url.scheme && h.host.equals(url.host, ignoreCase = true) && h.port == url.port
  }
}
