package net.mikesimone.backchannel.immich

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.util.DynamicTheme
import org.thoughtcrime.securesms.util.RemoteConfig
import java.io.File

/**
 * Backchannel (Mike 2026-11-04): pick photos and videos straight from the
 * user's Immich server. Returns local file URIs of the downloaded items; the
 * caller runs them through the normal media editor. See [ImmichClient].
 *
 * Backchannel, Mike 2026-11-11: the connect screen signs in through the
 * server's own login page ([ImmichLoginActivity]), with an API key as the
 * advanced option, and takes an optional address at home. A 401 later shows
 * "Signed out of Immich" with a button to sign in again. "Sign in with
 * browser" uses [ImmichBrowserSso] for providers that block WebViews (Google);
 * the WebView sign-in switches to it by itself when it reaches Google.
 */
class ImmichPickerActivity : AppCompatActivity() {

  private val viewModel: ImmichPickerViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    viewModel.start(this)
    viewModel.checkBrowserSso()
    setContent {
      SignalTheme(isDarkMode = DynamicTheme.isDarkTheme(LocalContext.current)) {
        ImmichScreen(
          viewModel = viewModel,
          onClose = { finish() },
          onDone = { uris ->
            setResult(Activity.RESULT_OK, Intent().putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris)))
            finish()
          }
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    viewModel.checkBrowserSso()
  }

  override fun onResume() {
    super.onResume()
    viewModel.checkBrowserSso()
  }

  companion object {
    private const val EXTRA_URIS = "backchannel_immich_uris"
    private const val DIR = "backchannel_immich"

    /** Where downloads go. Cleared each time the picker opens. */
    fun downloadDir(context: Context): File = File(context.cacheDir, DIR).apply { mkdirs() }
  }

  object Contract : ActivityResultContract<Unit, List<Uri>>() {
    override fun createIntent(context: Context, input: Unit): Intent = Intent(context, ImmichPickerActivity::class.java)

    @Suppress("DEPRECATION")
    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
      if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
      return intent.getParcelableArrayListExtra<Uri>(EXTRA_URIS).orEmpty()
    }
  }
}

class ImmichPickerViewModel : ViewModel() {

  private val TAG = Log.tag(ImmichPickerViewModel::class.java)

  private lateinit var appContext: Context

  var connected by mutableStateOf(false)
  var signedOut by mutableStateOf(false)
  var auth by mutableStateOf<ImmichClient.Auth?>(null)
  var savedServer by mutableStateOf("")
  var savedHome by mutableStateOf("")
  private var pending: ImmichClient.Addresses? = null
  var tab by mutableStateOf(0)
  var error by mutableStateOf<String?>(null)
  var busy by mutableStateOf(false)
  var sending by mutableStateOf(false)

  val assets = mutableStateListOf<ImmichClient.Asset>()
  private var nextPage: Int? = 1
  private var loadJob: Job? = null
  var query by mutableStateOf("")
  private var activeQuery: String? = null

  val albums = mutableStateListOf<ImmichClient.Album>()
  var openAlbum by mutableStateOf<ImmichClient.Album?>(null)

  val selected = mutableStateListOf<ImmichClient.Asset>()
  val maxSelection: Int = RemoteConfig.maxAttachmentCount

  val thumbs = object : LruCache<String, ImageBitmap>(300) {}

  fun start(context: Context) {
    if (::appContext.isInitialized) return
    appContext = context.applicationContext
    connected = ImmichClient.isConnected(appContext)
    auth = ImmichClient.auth(appContext)
    savedServer = ImmichClient.server(appContext).orEmpty()
    savedHome = ImmichClient.home(appContext).orEmpty()
    viewModelScope.launch(Dispatchers.IO) { ImmichPickerActivity.downloadDir(appContext).listFiles()?.forEach { it.delete() } }
    if (connected) {
      viewModelScope.launch {
        busy = true
        // Home or main address: decided fresh each time the picker opens.
        withContext(Dispatchers.IO) { ImmichClient.refreshRoute(appContext) }
        reloadAssets()
      }
    }
  }

  fun connectWithKey(server: String, home: String, key: String) {
    busy = true
    error = null
    viewModelScope.launch {
      try {
        withContext(Dispatchers.IO) {
          ImmichClient.connectWithKey(appContext, ImmichClient.addresses(appContext, server, home), key)
        }
        onConnected()
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't connect to Immich", e)
        error = message(e)
      } finally {
        busy = false
      }
    }
  }

  /** Checks the addresses before the login page opens. Returns the address to sign in at, or null (with [error] set). */
  fun beginSignIn(server: String, home: String): String? {
    error = null
    return try {
      ImmichClient.addresses(appContext, server, home).also { pending = it }.main
    } catch (e: Exception) {
      error = message(e)
      null
    }
  }

  /** Signs in again at the saved addresses after a 401. */
  fun beginSignInAgain(): String? {
    if (savedServer.isEmpty()) return null
    pending = ImmichClient.Addresses(savedServer, savedHome.ifEmpty { null })
    return savedServer
  }

  /** Set to a provider address the screen should open in the browser (then cleared). */
  var openBrowser by mutableStateOf<String?>(null)
  var browserNote by mutableStateOf(false)
  private var finishingSso = false

  /** Browser sign-in (for Google and other providers that block WebViews), the way Immich's phone app signs in. */
  fun beginBrowserSignIn(server: String, home: String) {
    if (beginSignIn(server, home) != null) startBrowserSso()
  }

  fun beginBrowserSignInAgain() {
    if (beginSignInAgain() != null) startBrowserSso()
  }

  private fun startBrowserSso() {
    val addresses = pending ?: return
    busy = true
    error = null
    viewModelScope.launch {
      try {
        openBrowser = withContext(Dispatchers.IO) { ImmichClient.startBrowserSso(appContext, addresses) }
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't start the browser sign-in", e)
        error = message(e)
      } finally {
        busy = false
      }
    }
  }

  /** Finishes a browser sign-in once the browser came back to [ImmichOAuthCallbackActivity]. */
  fun checkBrowserSso() {
    if (!::appContext.isInitialized || finishingSso) return
    val sso = ImmichClient.pendingSso(appContext) ?: return
    if (sso.callbackUrl == null) return
    finishingSso = true
    busy = true
    error = null
    viewModelScope.launch {
      try {
        withContext(Dispatchers.IO) { ImmichClient.finishBrowserSso(appContext, sso) }
        browserNote = false
        onConnected()
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't finish the browser sign-in", e)
        error = appContext.getString(R.string.Immich__sign_in_failed)
      } finally {
        busy = false
        finishingSso = false
      }
    }
  }

  /** [result] from [ImmichLoginActivity]. */
  fun finishSignIn(result: ImmichLoginActivity.Result) {
    if (result.useBrowser) {
      browserNote = true
      startBrowserSso()
      return
    }
    val addresses = pending ?: return
    val token = result.token ?: return
    busy = true
    error = null
    viewModelScope.launch {
      try {
        withContext(Dispatchers.IO) { ImmichClient.connectWithSession(appContext, addresses, token) }
        onConnected()
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't sign in to Immich", e)
        error = message(e)
      } finally {
        busy = false
      }
    }
  }

  private fun onConnected() {
    connected = true
    signedOut = false
    auth = ImmichClient.auth(appContext)
    savedServer = ImmichClient.server(appContext).orEmpty()
    savedHome = ImmichClient.home(appContext).orEmpty()
    thumbs.evictAll()
    albums.clear()
    reloadAssets()
  }

  /** Signs out and forgets the token or key and both addresses. */
  fun signOut() {
    viewModelScope.launch { withContext(Dispatchers.IO) { ImmichClient.signOut(appContext) } }
    connected = false
    signedOut = false
    auth = null
    savedServer = ""
    savedHome = ""
    error = null
    assets.clear()
    albums.clear()
    selected.clear()
    openAlbum = null
    thumbs.evictAll()
  }

  /** The server turned down the token or key. */
  private fun onUnauthorized(e: Exception) {
    if (auth == ImmichClient.Auth.SESSION) {
      signedOut = true
    } else {
      ImmichClient.forgetCredentials(appContext)
      connected = false
      error = message(e)
    }
    assets.clear()
    albums.clear()
    selected.clear()
    openAlbum = null
  }

  private fun isUnauthorized(e: Exception) = e is ImmichClient.ImmichException && e.unauthorized

  fun search() {
    activeQuery = query.trim().ifEmpty { null }
    reloadAssets()
  }

  fun selectTab(index: Int) {
    tab = index
    if (index == 1 && albums.isEmpty()) loadAlbums()
  }

  fun open(album: ImmichClient.Album?) {
    openAlbum = album
    if (album != null) {
      query = ""
      activeQuery = null
    }
    reloadAssets()
  }

  fun reloadAssets() {
    loadJob?.cancel()
    assets.clear()
    nextPage = 1
    loadJob = null
    loadMore()
  }

  fun loadMore() {
    val page = nextPage ?: return
    if (loadJob?.isActive == true) return
    val albumId = if (tab == 1) openAlbum?.id else null
    val q = if (albumId == null) activeQuery else null
    loadJob = viewModelScope.launch {
      busy = true
      try {
        val result = withContext(Dispatchers.IO) { ImmichClient.assets(appContext, page, albumId, q) }
        assets.addAll(result.assets)
        nextPage = result.nextPage
        error = null
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't load Immich assets", e)
        nextPage = null
        if (isUnauthorized(e)) onUnauthorized(e) else error = if (q != null) appContext.getString(R.string.Immich__search_failed) else message(e)
      } finally {
        busy = false
      }
    }
  }

  private fun loadAlbums() {
    viewModelScope.launch {
      busy = true
      try {
        val list = withContext(Dispatchers.IO) { ImmichClient.albums(appContext) }
        albums.clear()
        albums.addAll(list)
        error = null
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't load Immich albums", e)
        if (isUnauthorized(e)) onUnauthorized(e) else error = message(e)
      } finally {
        busy = false
      }
    }
  }

  fun toggle(asset: ImmichClient.Asset) {
    if (selected.any { it.id == asset.id }) {
      selected.removeAll { it.id == asset.id }
    } else if (selected.size < maxSelection) {
      selected.add(asset)
    }
  }

  suspend fun thumbnail(id: String): ImageBitmap? {
    thumbs.get(id)?.let { return it }
    return withContext(Dispatchers.IO) {
      try {
        val bytes = ImmichClient.thumbnail(appContext, id)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also { thumbs.put(id, it) }
      } catch (e: Exception) {
        null
      }
    }
  }

  fun send(onDone: (List<Uri>) -> Unit) {
    if (selected.isEmpty() || sending) return
    sending = true
    error = null
    viewModelScope.launch {
      try {
        val dir = ImmichPickerActivity.downloadDir(appContext)
        val picked = selected.toList()
        val uris = withContext(Dispatchers.IO) { picked.map { Uri.fromFile(ImmichClient.download(appContext, it, dir)) } }
        onDone(uris)
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't download from Immich", e)
        if (isUnauthorized(e)) onUnauthorized(e) else error = message(e)
      } finally {
        sending = false
      }
    }
  }

  private fun message(e: Exception): String {
    return when (e) {
      is ImmichClient.ImmichException -> e.message ?: appContext.getString(R.string.Immich__cant_reach)
      else -> appContext.getString(R.string.Immich__cant_reach)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImmichScreen(viewModel: ImmichPickerViewModel, onClose: () -> Unit, onDone: (List<Uri>) -> Unit) {
  var menu by remember { mutableStateOf(false) }
  val signIn = rememberLauncherForActivityResult(ImmichLoginActivity.Contract) { result -> viewModel.finishSignIn(result) }
  val context = LocalContext.current
  LaunchedEffect(viewModel.openBrowser) {
    val url = viewModel.openBrowser ?: return@LaunchedEffect
    viewModel.openBrowser = null
    openInBrowser(context, url)
  }
  val startSignIn: (String?) -> Unit = { server -> if (server != null) signIn.launch(server) }

  BackHandler(enabled = viewModel.openAlbum != null) { viewModel.open(null) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(viewModel.openAlbum?.name ?: stringResource(R.string.Immich__title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
          IconButton(onClick = { if (viewModel.openAlbum != null) viewModel.open(null) else onClose() }) {
            Icon(painter = SignalIcons.ArrowStart.painter, contentDescription = null)
          }
        },
        actions = {
          if (viewModel.connected) {
            IconButton(onClick = { menu = true }) {
              Icon(painter = SignalIcons.MoreVertical.painter, contentDescription = null)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.Immich__sign_out)) },
                onClick = {
                  menu = false
                  viewModel.signOut()
                }
              )
            }
          }
        }
      )
    },
    floatingActionButton = {
      if (viewModel.selected.isNotEmpty()) {
        ExtendedFloatingActionButton(onClick = { viewModel.send(onDone) }) {
          if (viewModel.sending) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
          } else {
            Text(stringResource(R.string.Immich__add, viewModel.selected.size))
          }
        }
      }
    }
  ) { padding ->
    Column(
      modifier = Modifier
        .padding(padding)
        .fillMaxSize()
    ) {
      if (viewModel.signedOut) {
        SignedOutScreen(viewModel, onSignIn = { startSignIn(viewModel.beginSignInAgain()) }, onBrowser = { viewModel.beginBrowserSignInAgain() })
      } else if (!viewModel.connected) {
        SetupScreen(
          viewModel,
          onSignIn = { server, home -> startSignIn(viewModel.beginSignIn(server, home)) },
          onBrowser = { server, home -> viewModel.beginBrowserSignIn(server, home) }
        )
      } else {
        if (viewModel.openAlbum == null) {
          TabRow(selectedTabIndex = viewModel.tab) {
            Tab(selected = viewModel.tab == 0, onClick = { viewModel.selectTab(0) }, text = { Text(stringResource(R.string.Immich__photos)) })
            Tab(selected = viewModel.tab == 1, onClick = { viewModel.selectTab(1) }, text = { Text(stringResource(R.string.Immich__albums)) })
          }
        }
        viewModel.error?.let {
          Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
        when {
          viewModel.tab == 1 && viewModel.openAlbum == null -> AlbumGrid(viewModel)
          else -> {
            if (viewModel.tab == 0) SearchBox(viewModel)
            AssetGrid(viewModel)
          }
        }
      }
    }
  }
}

@Composable
private fun SetupScreen(viewModel: ImmichPickerViewModel, onSignIn: (server: String, home: String) -> Unit, onBrowser: (server: String, home: String) -> Unit) {
  var server by remember { mutableStateOf(viewModel.savedServer) }
  var home by remember { mutableStateOf(viewModel.savedHome) }
  var key by remember { mutableStateOf("") }
  var useKey by remember { mutableStateOf(false) }
  Column(
    modifier = Modifier
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    Text(stringResource(R.string.Immich__setup_intro), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
      value = server,
      onValueChange = { server = it },
      label = { Text(stringResource(R.string.Immich__server)) },
      placeholder = { Text("https://photos.example.com") },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
      modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
      value = home,
      onValueChange = { home = it },
      label = { Text(stringResource(R.string.Immich__home_address)) },
      placeholder = { Text("https://immich.home.example") },
      supportingText = { Text(stringResource(R.string.Immich__home_address_help)) },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = if (useKey) ImeAction.Next else ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { if (server.isNotBlank()) onSignIn(server, home) }),
      modifier = Modifier.fillMaxWidth()
    )
    if (viewModel.browserNote) Text(stringResource(R.string.Immich__google_needs_browser), style = MaterialTheme.typography.bodyMedium)
    viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (!useKey) {
      Button(onClick = { onSignIn(server, home) }, enabled = !viewModel.busy && server.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        if (viewModel.busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.Immich__sign_in))
      }
      Text(stringResource(R.string.Immich__sign_in_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      OutlinedButton(onClick = { onBrowser(server, home) }, enabled = !viewModel.busy && server.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.Immich__sign_in_browser))
      }
      Text(stringResource(R.string.Immich__sign_in_browser_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      TextButton(onClick = { useKey = true }) { Text(stringResource(R.string.Immich__use_api_key)) }
    } else {
      OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        label = { Text(stringResource(R.string.Immich__api_key)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { viewModel.connectWithKey(server, home, key) }),
        modifier = Modifier.fillMaxWidth()
      )
      Text(stringResource(R.string.Immich__api_key_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Button(onClick = { viewModel.connectWithKey(server, home, key) }, enabled = !viewModel.busy && server.isNotBlank() && key.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        if (viewModel.busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.Immich__connect))
      }
      TextButton(onClick = { useKey = false }) { Text(stringResource(R.string.Immich__use_sign_in)) }
    }
  }
}

@Composable
private fun SignedOutScreen(viewModel: ImmichPickerViewModel, onSignIn: () -> Unit, onBrowser: () -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text(stringResource(R.string.Immich__signed_out), style = MaterialTheme.typography.titleMedium)
    viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = onSignIn, enabled = !viewModel.busy) {
      if (viewModel.busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.Immich__sign_in_again))
    }
    OutlinedButton(onClick = onBrowser, enabled = !viewModel.busy) { Text(stringResource(R.string.Immich__sign_in_browser)) }
    Text(stringResource(R.string.Immich__sign_in_browser_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = { viewModel.signOut() }) { Text(stringResource(R.string.Immich__sign_out)) }
  }
}

/** Opens [url] in a Custom Tab (or any browser if none supports them). */
private fun openInBrowser(context: Context, url: String) {
  try {
    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
  } catch (e: ActivityNotFoundException) {
    Toast.makeText(context, R.string.Immich__no_browser, Toast.LENGTH_LONG).show()
  }
}

@Composable
private fun SearchBox(viewModel: ImmichPickerViewModel) {
  OutlinedTextField(
    value = viewModel.query,
    onValueChange = {
      viewModel.query = it
      if (it.isEmpty()) viewModel.search()
    },
    placeholder = { Text(stringResource(R.string.Immich__search)) },
    leadingIcon = { Icon(painter = SignalIcons.Search.painter, contentDescription = null) },
    trailingIcon = {
      if (viewModel.query.isNotEmpty()) {
        IconButton(onClick = {
          viewModel.query = ""
          viewModel.search()
        }) { Icon(painter = SignalIcons.X.painter, contentDescription = null) }
      }
    },
    singleLine = true,
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 8.dp)
  )
}

@Composable
private fun AssetGrid(viewModel: ImmichPickerViewModel) {
  if (viewModel.assets.isEmpty()) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      if (viewModel.busy) CircularProgressIndicator() else if (viewModel.error == null) Text(stringResource(R.string.Immich__nothing))
    }
    return
  }
  LazyVerticalGrid(
    columns = GridCells.Adaptive(104.dp),
    contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 96.dp),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
    modifier = Modifier.fillMaxSize()
  ) {
    itemsIndexed(viewModel.assets, key = { _, a -> a.id }) { index, asset ->
      if (index >= viewModel.assets.size - 30) {
        LaunchedEffect(viewModel.assets.size) { viewModel.loadMore() }
      }
      val order = viewModel.selected.indexOfFirst { it.id == asset.id }
      Box(
        modifier = Modifier
          .aspectRatio(1f)
          .clickable { viewModel.toggle(asset) }
      ) {
        Thumbnail(viewModel, asset.id, Modifier.fillMaxSize())
        if (asset.isVideo) {
          Text(
            "▶ " + (asset.duration ?: ""),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
              .align(Alignment.BottomStart)
              .padding(4.dp)
              .background(Color(0x99000000), RoundedCornerShape(4.dp))
              .padding(horizontal = 4.dp, vertical = 1.dp)
          )
        }
        Box(
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(6.dp)
            .size(24.dp)
            .clip(CircleShape)
            .background(if (order >= 0) MaterialTheme.colorScheme.primary else Color(0x55000000))
            .border(2.dp, Color.White, CircleShape),
          contentAlignment = Alignment.Center
        ) {
          if (order >= 0) Text("${order + 1}", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
        }
      }
    }
  }
}

@Composable
private fun AlbumGrid(viewModel: ImmichPickerViewModel) {
  if (viewModel.albums.isEmpty()) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      if (viewModel.busy) CircularProgressIndicator() else if (viewModel.error == null) Text(stringResource(R.string.Immich__no_albums))
    }
    return
  }
  LazyVerticalGrid(
    columns = GridCells.Adaptive(150.dp),
    contentPadding = PaddingValues(12.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier.fillMaxSize()
  ) {
    itemsIndexed(viewModel.albums, key = { _, a -> a.id }) { _, album ->
      Column(modifier = Modifier.clickable { viewModel.open(album) }) {
        Box(
          modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
          album.thumbnailId?.let { Thumbnail(viewModel, it, Modifier.fillMaxSize()) }
        }
        Row(modifier = Modifier.padding(top = 4.dp)) {
          Text(album.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        Text("${album.count}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun Thumbnail(viewModel: ImmichPickerViewModel, id: String, modifier: Modifier) {
  val bitmap by produceState(initialValue = viewModel.thumbs.get(id), id) {
    if (value == null) value = viewModel.thumbnail(id)
  }
  Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
    bitmap?.let { Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
  }
}
