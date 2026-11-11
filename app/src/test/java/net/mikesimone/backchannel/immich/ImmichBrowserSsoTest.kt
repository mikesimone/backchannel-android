package net.mikesimone.backchannel.immich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmichBrowserSsoTest {

  private val now = 1_000_000_000L

  @Test
  fun callbackAddressIsRecognized() {
    assertTrue(ImmichBrowserSso.isCallback("app.immich:///oauth-callback?code=c&state=s"))
    assertTrue(ImmichBrowserSso.isCallback("app.immich:/oauth-callback?code=c&state=s"))
    assertTrue(ImmichBrowserSso.isCallback("app.immich://oauth-callback?code=c"))
    assertFalse(ImmichBrowserSso.isCallback("app.immich:///something-else?code=c"))
    assertFalse(ImmichBrowserSso.isCallback("https://photos.example.com/api/oauth/mobile-redirect?code=c"))
    assertFalse(ImmichBrowserSso.isCallback(null))
  }

  @Test
  fun callbackIsNormalizedToTheThreeSlashForm() {
    assertEquals("app.immich:///oauth-callback?code=c&state=s", ImmichBrowserSso.normalizeCallback("app.immich:/oauth-callback?code=c&state=s"))
    assertEquals("app.immich:///oauth-callback?code=c", ImmichBrowserSso.normalizeCallback("app.immich:///oauth-callback?code=c"))
  }

  @Test
  fun queryParametersAreDecoded() {
    val url = "app.immich:///oauth-callback?code=a%2Fb&state=x%3Dy#frag"
    assertEquals("a/b", ImmichBrowserSso.queryParameter(url, "code"))
    assertEquals("x=y", ImmichBrowserSso.queryParameter(url, "state"))
    assertNull(ImmichBrowserSso.queryParameter(url, "missing"))
  }

  @Test
  fun returnIsAcceptedOnlyForTheSignInInProgress() {
    val url = "app.immich:///oauth-callback?code=c&state=s1"
    assertTrue(ImmichBrowserSso.accepts(url, "s1", now - 1000, now))
    assertFalse(ImmichBrowserSso.accepts(url, "s2", now - 1000, now))
    assertFalse(ImmichBrowserSso.accepts(url, null, now - 1000, now))
    assertFalse(ImmichBrowserSso.accepts(url, "s1", now - ImmichBrowserSso.MAX_AGE_MS - 1, now))
    assertFalse(ImmichBrowserSso.accepts("app.immich:///oauth-callback?state=s1", "s1", now, now))
  }

  @Test
  fun expectedStateFollowsTheServer() {
    assertEquals("ours", ImmichBrowserSso.expectedState("https://idp.example.org/authorize?client_id=x&state=ours", "ours"))
    assertEquals("theirs", ImmichBrowserSso.expectedState("https://idp.example.org/authorize?state=theirs", "ours"))
    assertEquals("ours", ImmichBrowserSso.expectedState("https://idp.example.org/authorize?client_id=x", "ours"))
  }

  @Test
  fun pkceMatchesRfc7636Example() {
    assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ImmichBrowserSso.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    val v = ImmichBrowserSso.newCodeVerifier()
    assertTrue(v.length in 43..128)
    assertTrue(v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
  }

  @Test
  fun googleSignInIsSpotted() {
    assertTrue(ImmichBrowserSso.isGoogleSignIn("https://accounts.google.com/o/oauth2/v2/auth?client_id=x"))
    assertTrue(ImmichBrowserSso.isGoogleSignIn("https://accounts.google.com/signin/oauth/error?authError=disallowed_useragent"))
    assertFalse(ImmichBrowserSso.isGoogleSignIn("https://photos.example.com/auth/login"))
    assertFalse(ImmichBrowserSso.isGoogleSignIn("https://evil.example.com/?next=accounts.google.com"))
  }
}
