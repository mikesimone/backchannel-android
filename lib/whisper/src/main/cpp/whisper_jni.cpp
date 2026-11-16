// JNI bridge between Backchannel (net.mikesimone.backchannel.whisper.WhisperNative) and whisper.cpp.
// One Engine per loaded model; transcribe() runs on the calling (background) thread.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <mutex>
#include <string>

#include "ggml-backend.h"
#include "whisper.h"

#define TAG "BackchannelWhisper"

namespace {

struct Engine {
  whisper_context *ctx = nullptr;
  std::atomic<bool> cancelled{false};
};

struct Call {
  Engine *engine;
  JNIEnv *env;
  jobject listener;
  jmethodID on_segment;
};

std::once_flag backends_loaded;

void log_to_android(ggml_log_level level, const char *text, void *) {
  int priority = ANDROID_LOG_DEBUG;
  switch (level) {
    case GGML_LOG_LEVEL_ERROR: priority = ANDROID_LOG_ERROR; break;
    case GGML_LOG_LEVEL_WARN: priority = ANDROID_LOG_WARN; break;
    case GGML_LOG_LEVEL_INFO: priority = ANDROID_LOG_INFO; break;
    default: return; // debug and continuation lines are too chatty
  }
  __android_log_write(priority, TAG, text);
}

std::string to_string(JNIEnv *env, jstring value) {
  if (value == nullptr) return {};
  const char *chars = env->GetStringUTFChars(value, nullptr);
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

// whisper.cpp text is UTF-8, but a segment can end inside a multi-byte character; NewStringUTF wants
// valid (modified) UTF-8, so build the Java string from UTF-16 code units instead.
jstring to_jstring(JNIEnv *env, const std::string &utf8) {
  std::u16string out;
  out.reserve(utf8.size());
  size_t i = 0;
  while (i < utf8.size()) {
    unsigned char c = utf8[i];
    uint32_t cp;
    size_t len;
    if (c < 0x80) { cp = c; len = 1; }
    else if ((c >> 5) == 0x6) { cp = c & 0x1f; len = 2; }
    else if ((c >> 4) == 0xe) { cp = c & 0x0f; len = 3; }
    else if ((c >> 3) == 0x1e) { cp = c & 0x07; len = 4; }
    else { i++; continue; }
    if (i + len > utf8.size()) break;
    bool ok = true;
    for (size_t k = 1; k < len; k++) {
      unsigned char cc = utf8[i + k];
      if ((cc >> 6) != 0x2) { ok = false; break; }
      cp = (cp << 6) | (cc & 0x3f);
    }
    if (!ok) { i++; continue; }
    i += len;
    if (cp >= 0x10000) {
      cp -= 0x10000;
      out.push_back(static_cast<char16_t>(0xd800 + (cp >> 10)));
      out.push_back(static_cast<char16_t>(0xdc00 + (cp & 0x3ff)));
    } else {
      out.push_back(static_cast<char16_t>(cp));
    }
  }
  return env->NewString(reinterpret_cast<const jchar *>(out.data()), static_cast<jsize>(out.size()));
}

void on_new_segment(whisper_context *ctx, whisper_state *, int n_new, void *user_data) {
  auto *call = static_cast<Call *>(user_data);
  if (call->listener == nullptr) return;
  const int total = whisper_full_n_segments(ctx);
  for (int i = total - n_new; i < total; i++) {
    jstring text = to_jstring(call->env, whisper_full_get_segment_text(ctx, i));
    call->env->CallVoidMethod(call->listener, call->on_segment, text);
    call->env->DeleteLocalRef(text);
    if (call->env->ExceptionCheck()) call->env->ExceptionClear();
  }
}

bool should_abort(void *user_data) {
  return static_cast<Engine *>(user_data)->cancelled.load();
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_net_mikesimone_backchannel_whisper_WhisperNative_open(JNIEnv *env, jobject, jstring model_path, jstring library_dir) {
  std::string dir = to_string(env, library_dir);
  std::call_once(backends_loaded, [&dir]() {
    ggml_log_set(log_to_android, nullptr);
    whisper_log_set(log_to_android, nullptr);
    ggml_backend_load_all_from_path(dir.c_str());
  });
  if (ggml_backend_dev_count() == 0) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "No ggml backend could be loaded from %s", dir.c_str());
    return 0;
  }

  whisper_context_params params = whisper_context_default_params();
  params.use_gpu = false;
  params.flash_attn = false;
  std::string path = to_string(env, model_path);
  whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), params);
  if (ctx == nullptr) return 0;
  auto *engine = new Engine();
  engine->ctx = ctx;
  return reinterpret_cast<jlong>(engine);
}

/** Returns {language code, text}, or null if whisper failed or was cancelled. */
JNIEXPORT jobjectArray JNICALL
Java_net_mikesimone_backchannel_whisper_WhisperNative_transcribe(JNIEnv *env, jobject, jlong handle, jfloatArray samples,
                                                                 jint threads, jstring language, jobject listener) {
  auto *engine = reinterpret_cast<Engine *>(handle);
  if (engine == nullptr) return nullptr;
  engine->cancelled.store(false);

  std::string lang = to_string(env, language);
  whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
  params.n_threads = threads;
  params.language = lang.empty() ? "auto" : lang.c_str();
  params.translate = false;
  params.no_timestamps = true;
  params.print_progress = false;
  params.print_realtime = false;
  params.print_special = false;
  params.print_timestamps = false;
  params.suppress_blank = true;
  params.suppress_nst = true;

  Call call{engine, env, listener, nullptr};
  if (listener != nullptr) {
    jclass cls = env->GetObjectClass(listener);
    call.on_segment = env->GetMethodID(cls, "onSegment", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(cls);
    if (call.on_segment == nullptr) {
      env->ExceptionClear();
      call.listener = nullptr;
    }
  }
  params.new_segment_callback = on_new_segment;
  params.new_segment_callback_user_data = &call;
  params.abort_callback = should_abort;
  params.abort_callback_user_data = engine;

  jsize count = env->GetArrayLength(samples);
  jfloat *pcm = env->GetFloatArrayElements(samples, nullptr);
  int result = whisper_full(engine->ctx, params, pcm, count);
  env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
  if (result != 0 || engine->cancelled.load()) return nullptr;

  std::string text;
  const int segments = whisper_full_n_segments(engine->ctx);
  for (int i = 0; i < segments; i++) text += whisper_full_get_segment_text(engine->ctx, i);
  const int lang_id = whisper_full_lang_id(engine->ctx);
  const char *detected = lang_id >= 0 ? whisper_lang_str(lang_id) : "";

  jclass string_class = env->FindClass("java/lang/String");
  jobjectArray out = env->NewObjectArray(2, string_class, nullptr);
  env->DeleteLocalRef(string_class);
  jstring lang_out = env->NewStringUTF(detected ? detected : "");
  jstring text_out = to_jstring(env, text);
  env->SetObjectArrayElement(out, 0, lang_out);
  env->SetObjectArrayElement(out, 1, text_out);
  return out;
}

JNIEXPORT void JNICALL
Java_net_mikesimone_backchannel_whisper_WhisperNative_cancel(JNIEnv *, jobject, jlong handle) {
  auto *engine = reinterpret_cast<Engine *>(handle);
  if (engine != nullptr) engine->cancelled.store(true);
}

JNIEXPORT void JNICALL
Java_net_mikesimone_backchannel_whisper_WhisperNative_close(JNIEnv *, jobject, jlong handle) {
  auto *engine = reinterpret_cast<Engine *>(handle);
  if (engine == nullptr) return;
  whisper_free(engine->ctx);
  delete engine;
}

JNIEXPORT jstring JNICALL
Java_net_mikesimone_backchannel_whisper_WhisperNative_systemInfo(JNIEnv *env, jobject) {
  return env->NewStringUTF(whisper_print_system_info());
}

}  // extern "C"
