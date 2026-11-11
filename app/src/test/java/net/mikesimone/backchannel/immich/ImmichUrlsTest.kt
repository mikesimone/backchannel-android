package net.mikesimone.backchannel.immich

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmichUrlsTest {

  @Test
  fun bareHostBecomesHttps() {
    assertEquals("https://photos.example.com", ImmichUrls.normalize("photos.example.com"))
  }

  @Test
  fun pathsAndSlashesAreDropped() {
    assertEquals("https://photos.example.com", ImmichUrls.normalize(" https://photos.example.com/ "))
    assertEquals("https://photos.example.com", ImmichUrls.normalize("https://photos.example.com/api"))
    assertEquals("https://photos.example.com", ImmichUrls.normalize("https://photos.example.com/auth/login?continue=/photos"))
  }

  @Test
  fun homeAddressDefaultsToHttpAndKeepsPort() {
    assertEquals("http://192.168.1.10:2283", ImmichUrls.normalize("192.168.1.10:2283", defaultScheme = "http"))
    assertEquals("http://192.168.1.10", ImmichUrls.normalize("http://192.168.1.10:80/"))
    assertEquals("https://photos.example.com:8443", ImmichUrls.normalize("https://photos.example.com:8443"))
  }

  @Test
  fun junkIsRejected() {
    assertNull(ImmichUrls.normalize(""))
    assertNull(ImmichUrls.normalize("   "))
    assertNull(ImmichUrls.normalize("not an address"))
    assertNull(ImmichUrls.normalize("ftp://photos.example.com"))
  }

  @Test
  fun httpsCheck() {
    assertTrue(ImmichUrls.isHttps("https://photos.example.com"))
    assertFalse(ImmichUrls.isHttps("http://192.168.1.10:2283"))
  }

  @Test
  fun oauthReturnIsDetected() {
    val server = "https://photos.example.com"
    assertTrue(ImmichUrls.isOAuthReturn(server, "https://photos.example.com/auth/login?code=abc&state=xyz"))
    assertTrue(ImmichUrls.isOAuthReturn(server, "https://PHOTOS.example.com/auth/login/?state=xyz&code=abc"))
  }

  @Test
  fun otherPagesAreNotOAuthReturns() {
    val server = "https://photos.example.com"
    assertFalse(ImmichUrls.isOAuthReturn(server, "https://photos.example.com/auth/login"))
    assertFalse(ImmichUrls.isOAuthReturn(server, "https://photos.example.com/auth/login?error=access_denied"))
    assertFalse(ImmichUrls.isOAuthReturn(server, "https://photos.example.com/photos?code=abc"))
    assertFalse(ImmichUrls.isOAuthReturn(server, "https://idp.example.org/auth/login?code=abc"))
    assertFalse(ImmichUrls.isOAuthReturn(server, "http://photos.example.com/auth/login?code=abc"))
    assertFalse(ImmichUrls.isOAuthReturn(server, "app.immich:///oauth-callback?code=abc"))
  }

  @Test
  fun tokenIsReadFromCookies() {
    val header = "immich_auth_type=password; immich_access_token=tok123; immich_is_authenticated=true"
    assertEquals("tok123", ImmichUrls.cookie(header, ImmichUrls.ACCESS_TOKEN_COOKIE))
    assertNull(ImmichUrls.cookie("immich_is_authenticated=true", ImmichUrls.ACCESS_TOKEN_COOKIE))
    assertNull(ImmichUrls.cookie("immich_access_token=", ImmichUrls.ACCESS_TOKEN_COOKIE))
    assertNull(ImmichUrls.cookie(null, ImmichUrls.ACCESS_TOKEN_COOKIE))
    assertEquals(listOf("a", "b"), ImmichUrls.cookieNames("a=1; b=2"))
  }

  @Test
  fun pong() {
    assertTrue(ImmichUrls.isPong("{\"res\":\"pong\"}"))
    assertTrue(ImmichUrls.isPong("{ \"res\" : \"pong\" }"))
    assertFalse(ImmichUrls.isPong("<html>pong</html>"))
    assertFalse(ImmichUrls.isPong(null))
  }

  @Test
  fun homeIsUsedOnlyWhenItAnswers() {
    val main = "https://photos.example.com"
    val home = "http://192.168.1.10:2283"
    assertEquals(home, ImmichUrls.choose(main, home, homeAnswered = true))
    assertEquals(main, ImmichUrls.choose(main, home, homeAnswered = false))
    assertEquals(main, ImmichUrls.choose(main, null, homeAnswered = true))
  }

  @Test
  fun cleartextOnlyToTheHomeAddress() {
    val home = "http://192.168.1.10:2283"
    assertTrue(ImmichUrls.cleartextAllowed("https://photos.example.com/api/albums".toHttpUrl(), home))
    assertTrue(ImmichUrls.cleartextAllowed("http://192.168.1.10:2283/api/albums".toHttpUrl(), home))
    assertFalse(ImmichUrls.cleartextAllowed("http://192.168.1.10:8080/api/albums".toHttpUrl(), home))
    assertFalse(ImmichUrls.cleartextAllowed("http://192.168.1.11:2283/api/albums".toHttpUrl(), home))
    assertFalse(ImmichUrls.cleartextAllowed("http://photos.example.com/api/albums".toHttpUrl(), null))
  }
}
