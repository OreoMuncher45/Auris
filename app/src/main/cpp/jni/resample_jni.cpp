/*
 * JNI bridge to the streaming rate converter.
 *
 * The converter itself lives in the shared analyzer library
 * (native/analyzer/resampler.cpp); this file only moves bytes across the
 * boundary and keeps the native object alive between calls.
 */

#include <jni.h>

#include <vector>

#include "analyzer/resampler.h"

namespace {

bitchord::smart::StreamResampler* Converter(jlong handle) {
  return reinterpret_cast<bitchord::smart::StreamResampler*>(handle);
}

}  // namespace

extern "C" {

// Returns the converter's address, or zero when the rate pair is one it
// cannot do exactly.
JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_smart_MdxResampler_nativeCreate(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jdouble input_rate,
    jdouble output_rate,
    jint channels) {
  auto* converter = new bitchord::smart::StreamResampler(
      static_cast<double>(input_rate),
      static_cast<double>(output_rate),
      static_cast<int>(channels));
  if (!converter->supported()) {
    delete converter;
    return 0;
  }
  return reinterpret_cast<jlong>(converter);
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_smart_MdxResampler_nativeLookahead(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jlong handle) {
  return static_cast<jint>(Converter(handle)->lookahead());
}

JNIEXPORT jfloatArray JNICALL
Java_com_music_bitchord_playback_smart_MdxResampler_nativeProcess(
    JNIEnv* env,
    jclass /* clazz */,
    jlong handle,
    jfloatArray input,
    jint frames) {
  const jsize count = env->GetArrayLength(input);
  std::vector<float> samples(static_cast<size_t>(count));
  if (count > 0) {
    env->GetFloatArrayRegion(input, 0, count, samples.data());
  }

  std::vector<float> converted;
  Converter(handle)->Process(samples.data(), static_cast<size_t>(frames), &converted);

  const jsize produced = static_cast<jsize>(converted.size());
  jfloatArray result = env->NewFloatArray(produced);
  if (result == nullptr) return nullptr;
  if (produced > 0) {
    env->SetFloatArrayRegion(result, 0, produced, converted.data());
  }
  return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_music_bitchord_playback_smart_MdxResampler_nativeFlush(
    JNIEnv* env,
    jclass /* clazz */,
    jlong handle) {
  std::vector<float> converted;
  Converter(handle)->Flush(&converted);

  const jsize produced = static_cast<jsize>(converted.size());
  jfloatArray result = env->NewFloatArray(produced);
  if (result == nullptr) return nullptr;
  if (produced > 0) {
    env->SetFloatArrayRegion(result, 0, produced, converted.data());
  }
  return result;
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_smart_MdxResampler_nativeReset(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jlong handle) {
  Converter(handle)->Reset();
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_smart_MdxResampler_nativeDestroy(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jlong handle) {
  delete Converter(handle);
}

}  // extern "C"
