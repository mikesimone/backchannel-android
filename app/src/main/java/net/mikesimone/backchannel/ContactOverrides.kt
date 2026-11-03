package net.mikesimone.backchannel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.contacts.avatars.ContactPhoto
import org.thoughtcrime.securesms.contacts.avatars.SystemContactPhoto
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Backchannel (Mike 2026-11-02): two things Signal won't do.
 *
 * 1. Link a Signal person to a phone contact by hand. Signal matches people to
 *    contacts by phone number, which breaks when someone hides their number
 *    ("Who can find me by number: Nobody"). A manual link uses the contact's
 *    name and photo and never breaks on its own: if the contact is later
 *    deleted or unreadable, the last name and photo are kept.
 * 2. Set your own photo for anyone, in or out of your phone contacts.
 *
 * All of it is stored on this phone only. A custom photo always wins; a linked
 * contact's photo is treated like any other phone-contact photo. A Signal
 * nickname still wins over the linked contact's name.
 */
object ContactOverrides {

  private val TAG = Log.tag(ContactOverrides::class.java)

  private const val PREFS = "backchannel_contact_links"
  private const val NAME = "name_"
  private const val LOOKUP = "lookup_"
  private const val PHOTO_DIR = "backchannel_contact_photos"
  private const val PHOTO_SIZE = 640

  private class Entry(val name: String?, val linkedPhoto: File?, val customPhoto: File?)

  private val cache = ConcurrentHashMap<String, Entry>()

  @Volatile
  private var loaded = false

  private fun context(): Context = AppDependencies.application

  private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private fun photoDir(context: Context): File = File(context.filesDir, PHOTO_DIR).apply { mkdirs() }

  private fun linkedFile(context: Context, key: String) = File(photoDir(context), "$key-linked.jpg")

  private fun customFile(context: Context, key: String) = File(photoDir(context), "$key-custom.jpg")

  private fun ensureLoaded() {
    if (loaded) return
    synchronized(this) {
      if (loaded) return
      val context = try {
        context()
      } catch (e: Throwable) {
        loaded = true // no app context (unit tests): nothing to override
        return
      }
      val keys = HashSet<String>()
      prefs(context).all.keys.forEach { k ->
        when {
          k.startsWith(NAME) -> keys.add(k.removePrefix(NAME))
          k.startsWith(LOOKUP) -> keys.add(k.removePrefix(LOOKUP))
        }
      }
      photoDir(context).listFiles()?.forEach { f -> keys.add(f.name.substringBefore('-')) }
      keys.forEach { reload(context, it) }
      loaded = true
    }
  }

  private fun reload(context: Context, key: String) {
    val name = prefs(context).getString(NAME + key, null)
    val linked = linkedFile(context, key).takeIf { it.exists() }
    val custom = customFile(context, key).takeIf { it.exists() }
    if (name == null && linked == null && custom == null) {
      cache.remove(key)
    } else {
      cache[key] = Entry(name, linked, custom)
    }
  }

  private fun entry(id: RecipientId): Entry? {
    if (id.isUnknown) return null
    ensureLoaded()
    return cache[id.serialize()]
  }

  @JvmStatic
  fun linkedName(id: RecipientId): String? = entry(id)?.name

  @JvmStatic
  fun isLinked(id: RecipientId): Boolean {
    ensureLoaded()
    return prefs(context()).contains(LOOKUP + id.serialize())
  }

  @JvmStatic
  fun customPhoto(id: RecipientId): ContactPhoto? = entry(id)?.customPhoto?.let { SystemContactPhoto(id, Uri.fromFile(it), it.lastModified()) }

  @JvmStatic
  fun linkedPhoto(id: RecipientId): ContactPhoto? = entry(id)?.linkedPhoto?.let { SystemContactPhoto(id, Uri.fromFile(it), it.lastModified()) }

  @JvmStatic
  fun hasCustomPhoto(id: RecipientId): Boolean = entry(id)?.customPhoto != null

  /** Links [id] to the phone contact at [contactUri] (from a contact picker). */
  @WorkerThread
  fun link(context: Context, id: RecipientId, contactUri: Uri): Boolean {
    val key = id.serialize()
    val resolver = context.contentResolver
    var lookupUri: Uri? = null
    var name: String? = null
    resolver.query(contactUri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
      if (c.moveToFirst()) {
        lookupUri = ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1))
        name = c.getString(2)
      }
    }
    if (lookupUri == null) {
      Log.w(TAG, "Couldn't read the picked contact")
      return false
    }
    prefs(context).edit()
      .putString(LOOKUP + key, lookupUri.toString())
      .putString(NAME + key, name?.takeIf { it.isNotBlank() })
      .commit()
    copyContactPhoto(context, key, contactUri)
    reload(context, key)
    Recipient.live(id).refresh()
    return true
  }

  @WorkerThread
  fun unlink(context: Context, id: RecipientId) {
    val key = id.serialize()
    prefs(context).edit().remove(LOOKUP + key).remove(NAME + key).commit()
    linkedFile(context, key).delete()
    reload(context, key)
    Recipient.live(id).refresh()
  }

  /** Sets your own photo for [id] from an image [imageUri], cropped square. */
  @WorkerThread
  fun setCustomPhoto(context: Context, id: RecipientId, imageUri: Uri): Boolean {
    val key = id.serialize()
    return try {
      val bitmap = Glide.with(context.applicationContext).asBitmap().load(imageUri).centerCrop().submit(PHOTO_SIZE, PHOTO_SIZE).get()
      val tmp = File(photoDir(context), "$key-custom.tmp")
      FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
      tmp.renameTo(customFile(context, key))
      reload(context, key)
      Recipient.live(id).refresh()
      true
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't set custom photo", e)
      false
    }
  }

  @WorkerThread
  fun clearCustomPhoto(context: Context, id: RecipientId) {
    val key = id.serialize()
    customFile(context, key).delete()
    reload(context, key)
    Recipient.live(id).refresh()
  }

  /** Picks up name and photo changes in linked contacts. Keeps the old values if a contact is gone. */
  @WorkerThread
  @JvmStatic
  fun refreshAll(context: Context) {
    ensureLoaded()
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
    val prefs = prefs(context)
    prefs.all.keys.filter { it.startsWith(LOOKUP) }.forEach { k ->
      val key = k.removePrefix(LOOKUP)
      try {
        val lookup = Uri.parse(prefs.getString(k, null) ?: return@forEach)
        val contactUri = ContactsContract.Contacts.lookupContact(context.contentResolver, lookup) ?: return@forEach
        var name: String? = null
        context.contentResolver.query(contactUri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
          if (c.moveToFirst()) name = c.getString(0)
        }
        val changedName = !name.isNullOrBlank() && name != prefs.getString(NAME + key, null)
        if (changedName) prefs.edit().putString(NAME + key, name).commit()
        val changedPhoto = copyContactPhoto(context, key, contactUri)
        if (changedName || changedPhoto) {
          reload(context, key)
          Recipient.live(RecipientId.from(key)).refresh()
        }
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't refresh a linked contact", e)
      }
    }
  }

  /** Copies the contact's photo to our own file. Returns true if the stored photo changed. */
  private fun copyContactPhoto(context: Context, key: String, contactUri: Uri): Boolean {
    val bytes = try {
      ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, contactUri, true)?.use { it.readBytes() }
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't read contact photo", e)
      return false
    } ?: return false
    val file = linkedFile(context, key)
    if (file.exists() && file.readBytes().contentEquals(bytes)) return false
    val tmp = File(photoDir(context), "$key-linked.tmp")
    tmp.writeBytes(bytes)
    tmp.renameTo(file)
    return true
  }
}
