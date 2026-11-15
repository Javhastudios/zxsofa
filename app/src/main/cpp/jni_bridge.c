/* Puente JNI entre Kotlin (com.zxsofa.emu.NativeCore) y zxcore. */
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <string.h>

#include "zxcore.h"

#define TAG "ZXSofaCore"
#define FN(name) Java_com_zxsofa_emu_NativeCore_##name

static void android_log(int level, const char *msg)
{
   int prio = ANDROID_LOG_INFO;
   switch (level)
   {
      case 0: prio = ANDROID_LOG_DEBUG; break;
      case 1: prio = ANDROID_LOG_INFO; break;
      case 2: prio = ANDROID_LOG_WARN; break;
      case 3: prio = ANDROID_LOG_ERROR; break;
   }
   __android_log_write(prio, TAG, msg);
}

JNIEXPORT void JNICALL FN(nativeInit)(JNIEnv *env, jobject thiz, jstring sys, jstring save)
{
   const char *s = (*env)->GetStringUTFChars(env, sys, NULL);
   const char *v = (*env)->GetStringUTFChars(env, save, NULL);
   zx_init(s, v, android_log);
   (*env)->ReleaseStringUTFChars(env, sys, s);
   (*env)->ReleaseStringUTFChars(env, save, v);
}

JNIEXPORT void JNICALL FN(nativeClearOptions)(JNIEnv *env, jobject thiz)
{
   zx_clear_options();
}

JNIEXPORT void JNICALL FN(nativeSetOption)(JNIEnv *env, jobject thiz, jstring key, jstring value)
{
   const char *k = (*env)->GetStringUTFChars(env, key, NULL);
   const char *v = (*env)->GetStringUTFChars(env, value, NULL);
   zx_set_option(k, v);
   (*env)->ReleaseStringUTFChars(env, key, k);
   (*env)->ReleaseStringUTFChars(env, value, v);
}

JNIEXPORT jboolean JNICALL FN(nativeLoad)(JNIEnv *env, jobject thiz, jstring path)
{
   const char *p = (*env)->GetStringUTFChars(env, path, NULL);
   int ok = zx_load(p);
   (*env)->ReleaseStringUTFChars(env, path, p);
   return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL FN(nativeUnload)(JNIEnv *env, jobject thiz)
{
   zx_unload();
}

JNIEXPORT void JNICALL FN(nativeReset)(JNIEnv *env, jobject thiz)
{
   zx_reset();
}

/* Ejecuta un fotograma y copia el audio generado en 'audio' (int16 estereo
   intercalado). Devuelve el numero de frames estereo copiados. */
JNIEXPORT jint JNICALL FN(nativeRunFrame)(JNIEnv *env, jobject thiz, jshortArray audio)
{
   jsize len = (*env)->GetArrayLength(env, audio);
   jshort *buf;
   size_t n;

   zx_run_frame();
   buf = (*env)->GetPrimitiveArrayCritical(env, audio, NULL);
   n = zx_audio_read((int16_t *)buf, (size_t)len / 2);
   (*env)->ReleasePrimitiveArrayCritical(env, audio, buf, 0);
   return (jint)n;
}

/* (ancho << 16) | alto del ultimo fotograma, o 0 si aun no hay. */
JNIEXPORT jint JNICALL FN(nativeFrameSize)(JNIEnv *env, jobject thiz)
{
   int w = 0, h = 0;
   if (!zx_frame(&w, &h, NULL))
      return 0;
   return (w << 16) | h;
}

/* Copia el fotograma a un Bitmap RGB_565 del mismo tamano. */
JNIEXPORT jboolean JNICALL FN(nativeCopyFrame)(JNIEnv *env, jobject thiz, jobject bitmap)
{
   AndroidBitmapInfo info;
   void *pixels;
   int w, h, pitch, y;
   const uint16_t *frame = zx_frame(&w, &h, &pitch);

   if (!frame)
      return JNI_FALSE;
   if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
      return JNI_FALSE;
   if (info.format != ANDROID_BITMAP_FORMAT_RGB_565 || (int)info.width != w || (int)info.height != h)
      return JNI_FALSE;
   if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
      return JNI_FALSE;
   for (y = 0; y < h; y++)
      memcpy((uint8_t *)pixels + y * info.stride, frame + y * pitch, (size_t)w * 2);
   AndroidBitmap_unlockPixels(env, bitmap);
   return JNI_TRUE;
}

JNIEXPORT void JNICALL FN(nativeSetJoystickType)(JNIEnv *env, jobject thiz, jint type)
{
   zx_set_joystick_type(type);
}

JNIEXPORT void JNICALL FN(nativeSetJoypad)(JNIEnv *env, jobject thiz, jint mask)
{
   zx_set_joypad((unsigned)mask);
}

JNIEXPORT void JNICALL FN(nativeSetKey)(JNIEnv *env, jobject thiz, jint code, jboolean down)
{
   zx_set_key((unsigned)code, down == JNI_TRUE);
}

JNIEXPORT void JNICALL FN(nativeReleaseAll)(JNIEnv *env, jobject thiz)
{
   zx_release_all();
}

JNIEXPORT jboolean JNICALL FN(nativeSaveState)(JNIEnv *env, jobject thiz, jstring path)
{
   const char *p = (*env)->GetStringUTFChars(env, path, NULL);
   int ok = zx_state_save(p);
   (*env)->ReleaseStringUTFChars(env, path, p);
   return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL FN(nativeLoadState)(JNIEnv *env, jobject thiz, jstring path)
{
   const char *p = (*env)->GetStringUTFChars(env, path, NULL);
   int ok = zx_state_load(p);
   (*env)->ReleaseStringUTFChars(env, path, p);
   return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL FN(nativeTakeMessage)(JNIEnv *env, jobject thiz)
{
   const char *m = zx_take_message();
   return m ? (*env)->NewStringUTF(env, m) : NULL;
}

JNIEXPORT jint JNICALL FN(nativeTapeCount)(JNIEnv *env, jobject thiz)
{
   return zx_tape_count();
}

JNIEXPORT jint JNICALL FN(nativeTapeIndex)(JNIEnv *env, jobject thiz)
{
   return zx_tape_index();
}

JNIEXPORT jboolean JNICALL FN(nativeTapeSelect)(JNIEnv *env, jobject thiz, jint index)
{
   return zx_tape_select((unsigned)index) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL FN(nativeTapeCatalog)(JNIEnv *env, jobject thiz)
{
   return (*env)->NewStringUTF(env, zx_tape_catalog());
}

JNIEXPORT jboolean JNICALL FN(nativeTapeLoadBlock)(JNIEnv *env, jobject thiz, jint block)
{
   return zx_tape_load_block(block) ? JNI_TRUE : JNI_FALSE;
}
