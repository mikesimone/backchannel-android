package net.mikesimone.backchannel.immich

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.util.DynamicTheme

/**
 * Backchannel, Mike 2026-11-11: signs in to Immich through the server's own
 * web login page, so whatever the server offers works: email and password,
 * and/or OAuth single sign-on with any provider. Returns the session token.
 *
 * Two ways a sign-in is noticed, neither tied to a provider:
 *  - OAuth: the provider sends the WebView back to `<server>/auth/login?code=...`.
 *    That return is caught before the web app runs, and finished here with
 *    POST /api/oauth/callback, which answers with the token.
 *  - Anything else (password, or a callback the web app finished itself): the
 *    web app's `immich_access_token` cookie appears for the server.
 *
 * The server's cookies and web storage are cleared before and after, so no
 * second copy of the session stays in the WebView.
 */
class ImmichLoginActivity : AppCompatActivity() {

  private val TAG = Log.tag(ImmichLoginActivity::class.java)

  private var server = ""
  private var webView: WebView? = null
  private var finishing = false
  private var completingOAuth = false
  private var progress by mutableStateOf(true)

  private val handler = Handler(Looper.getMainLooper())
  private val poll = object : Runnable {
    override fun run() {
      checkCookie()
      if (!finishing) handler.postDelayed(this, 500)
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    server = intent.getStringExtra(EXTRA_SERVER).orEmpty()
    if (server.isEmpty()) {
      finish()
      return
    }
    clearServerData(server)

    setContent {
      SignalTheme(isDarkMode = DynamicTheme.isDarkTheme(LocalContext.current)) {
        LoginScreen()
      }
    }
    handler.postDelayed(poll, 500)
  }

  @OptIn(ExperimentalMaterial3Api::class)
  @androidx.compose.runtime.Composable
  private fun LoginScreen() {
    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text(stringResource(R.string.Immich__sign_in_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
          navigationIcon = {
            IconButton(onClick = { finish() }) {
              Icon(painter = SignalIcons.X.painter, contentDescription = null)
            }
          }
        )
      }
    ) { padding ->
      Box(
        modifier = Modifier
          .padding(padding)
          .fillMaxSize()
      ) {
        AndroidView(
          factory = { context -> createWebView(context) },
          modifier = Modifier.fillMaxSize()
        )
        if (progress) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
      }
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun createWebView(context: Context): WebView {
    val view = WebView(context)
    view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    view.settings.javaScriptEnabled = true
    view.settings.domStorageEnabled = true
    view.settings.allowFileAccess = false
    view.settings.allowContentAccess = false
    view.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        val url = request.url.toString()
        if (ImmichUrls.isOAuthReturn(server, url)) {
          completeOAuth(url)
          return true
        }
        // Custom schemes (an app's own redirect, intent:...) have nowhere to go here.
        val scheme = request.url.scheme?.lowercase()
        return scheme != "https" && scheme != "http"
      }

      override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        progress = true
        if (ImmichUrls.isOAuthReturn(server, url)) {
          view.stopLoading()
          completeOAuth(url)
        }
      }

      override fun onPageFinished(view: WebView, url: String) {
        progress = completingOAuth
        checkCookie()
      }

      override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        checkCookie()
      }
    }
    webView = view
    view.loadUrl(ImmichUrls.loginPage(server))
    return view
  }

  private fun completeOAuth(returnUrl: String) {
    if (completingOAuth || finishing) return
    completingOAuth = true
    progress = true
    val cookies = CookieManager.getInstance().getCookie(server)
    lifecycleScope.launch {
      val token = try {
        withContext(Dispatchers.IO) { ImmichClient.finishOAuth(server, returnUrl, cookies) }
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't finish the OAuth sign-in ourselves", e)
        // The web app may have finished it first; then the cookie has the token.
        tokenFromCookie()
      }
      if (token != null) {
        done(token)
      } else {
        completingOAuth = false
        Toast.makeText(this@ImmichLoginActivity, R.string.Immich__sign_in_failed, Toast.LENGTH_LONG).show()
        clearServerData(server)
        webView?.loadUrl(ImmichUrls.loginPage(server))
      }
    }
  }

  private fun tokenFromCookie(): String? = ImmichUrls.cookie(CookieManager.getInstance().getCookie(server), ImmichUrls.ACCESS_TOKEN_COOKIE)

  private fun checkCookie() {
    if (finishing || completingOAuth) return
    tokenFromCookie()?.let { done(it) }
  }

  private fun done(token: String) {
    if (finishing) return
    finishing = true
    handler.removeCallbacks(poll)
    webView?.stopLoading()
    clearServerData(server)
    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_TOKEN, token))
    finish()
  }

  override fun onDestroy() {
    handler.removeCallbacks(poll)
    if (!finishing) clearServerData(server)
    webView?.destroy()
    webView = null
    super.onDestroy()
  }

  companion object {
    private const val EXTRA_SERVER = "backchannel_immich_server"
    private const val EXTRA_TOKEN = "backchannel_immich_token"

    /** Expires every cookie the WebView holds for [server] and drops its web storage. */
    fun clearServerData(server: String) {
      if (server.isEmpty()) return
      try {
        val manager = CookieManager.getInstance()
        for (name in ImmichUrls.cookieNames(manager.getCookie(server))) {
          manager.setCookie(server, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
        }
        manager.flush()
        WebStorage.getInstance().deleteOrigin(server)
      } catch (e: Exception) {
        // No WebView installed or it failed to load; there is nothing to clear.
      }
    }
  }

  /** Input: the main server address. Output: the session token, or null if cancelled. */
  object Contract : ActivityResultContract<String, String?>() {
    override fun createIntent(context: Context, input: String): Intent = Intent(context, ImmichLoginActivity::class.java).putExtra(EXTRA_SERVER, input)

    override fun parseResult(resultCode: Int, intent: Intent?): String? {
      if (resultCode != Activity.RESULT_OK) return null
      return intent?.getStringExtra(EXTRA_TOKEN)
    }
  }
}
