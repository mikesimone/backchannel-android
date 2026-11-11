package net.mikesimone.backchannel.immich

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.annotation.WorkerThread
import org.signal.core.util.logging.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Backchannel, Mike 2026-11-11: picks between the user's main Immich address
 * and their optional "address at home". The home address is used only when it
 * answers Immich's ping within about a second; the choice is kept until the
 * phone's default network changes (Wi-Fi to mobile data and back, a VPN coming
 * up...). No Wi-Fi name is read, so no location permission is needed.
 */
internal object ImmichRouter {

  private val TAG = Log.tag(ImmichRouter::class.java)

  private data class Choice(val main: String, val home: String, val generation: Int, val base: String)

  private val generation = AtomicInteger()
  private val watching = AtomicBoolean()

  @Volatile
  private var choice: Choice? = null

  /** Forget the choice; the next request probes again. */
  fun invalidate() {
    generation.incrementAndGet()
    choice = null
  }

  /** The address to use now. Probes the home address with [ping] when nothing is cached. */
  @WorkerThread
  fun base(context: Context, main: String, home: String?, ping: (String) -> Boolean): String {
    if (home == null) return main
    watch(context)
    cached(main, home)?.let { return it }
    synchronized(this) {
      cached(main, home)?.let { return it }
      val gen = generation.get()
      val answered = try {
        ping(home)
      } catch (e: Exception) {
        false
      }
      val base = ImmichUrls.choose(main, home, answered)
      Log.i(TAG, if (base == home) "Using the home address" else "Using the main address")
      if (generation.get() == gen) choice = Choice(main, home, gen, base)
      return base
    }
  }

  /** The home address just failed to connect: use the main one until the network changes. */
  fun useMain(main: String, home: String) {
    choice = Choice(main, home, generation.get(), main)
  }

  private fun cached(main: String, home: String): String? {
    val c = choice ?: return null
    return if (c.main == main && c.home == home && c.generation == generation.get()) c.base else null
  }

  private fun watch(context: Context) {
    if (!watching.compareAndSet(false, true)) return
    try {
      val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
      cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
        // The first callback reports the network we're already on; only changes after it count.
        private var current: Network? = null
        private var first = true

        override fun onAvailable(network: Network) {
          val changed = !first && network != current
          first = false
          current = network
          if (changed) invalidate()
        }

        override fun onLost(network: Network) {
          if (network == current) current = null
          first = false
          invalidate()
        }
      })
    } catch (e: Exception) {
      Log.w(TAG, "Can't watch network changes", e)
      watching.set(false)
    }
  }
}
