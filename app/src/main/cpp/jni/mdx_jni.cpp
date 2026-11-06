/*
 * JNI bridge to the MDX-Net separation front end.
 *
 * The transform lives in the shared analyzer library (native/analyzer/mdx_stft.cpp)
 * because it is the same code the desktop build would use; this file only
 * moves bytes across the boundary.
 */

#include <jni.h>

#include <vector>

#include "analyzer/mdx_stft.h"

extern "C" {

// Takes the stereo pair as one interleaved array, which is the shape the
// audio pipeline already has, and returns the model's tensor: four channels
// of complex bins over a frame axis, flattened as [channel][bin][frame].
JNIEXPORT jfloatArray JNICALL
Java_com_music_bitchord_playback_smart_MdxStft_nativeForward(
    JNIEnv* env,
    jclass /* clazz */,
    jfloatArray pcm,
    jint frames) {
  const jsize count = env->GetArrayLength(pcm);
  std::vector<float> interleaved(static_cast<size_t>(count));
  if (count > 0) env->GetFloatArrayRegion(pcm, 0, count, interleaved.data());

  const bitchord::smart::MdxTensor tensor =
      bitchord::smart::MdxForward(interleaved, static_cast<size_t>(frames));

  const jsize produced = static_cast<jsize>(tensor.values.size());
  jfloatArray result = env->NewFloatArray(produced);
  if (result == nullptr) return nullptr;
  if (produced > 0) {
    env->SetFloatArrayRegion(result, 0, produced, tensor.values.data());
  }
  return result;
}

// The model's output goes back through the inverse transform unchanged.
JNIEXPORT jfloatArray JNICALL
Java_com_music_bitchord_playback_smart_MdxStft_nativeInverse(
    JNIEnv* env,
    jclass /* clazz */,
    jfloatArray tensor_values,
    jint frames,
    jint bins) {
  const jsize count = env->GetArrayLength(tensor_values);
  std::vector<float> values(static_cast<size_t>(count));
  if (count > 0) env->GetFloatArrayRegion(tensor_values, 0, count, values.data());

  bitchord::smart::MdxTensor tensor;
  tensor.values = std::move(values);
  tensor.frames = static_cast<size_t>(frames);
  tensor.bins = static_cast<size_t>(bins);

  const std::vector<float> pcm = bitchord::smart::MdxInverse(tensor);

  const jsize produced = static_cast<jsize>(pcm.size());
  jfloatArray result = env->NewFloatArray(produced);
  if (result == nullptr) return nullptr;
  if (produced > 0) {
    env->SetFloatArrayRegion(result, 0, produced, pcm.data());
  }
  return result;
}

}  // extern "C"
