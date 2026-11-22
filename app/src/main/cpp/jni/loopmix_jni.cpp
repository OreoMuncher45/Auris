// JNI bridge for the Automix loop mix renderer (native/loopmix/). Every call
// but render() is made while the mix is being set up; render() runs on the
// player's playback thread, inside its audio sink.
//
// Copyright (C) 2026 Kushagra Singh. GPL-3.0-or-later, with the rest of BitChord.
#include <jni.h>

#include <algorithm>
#include <new>

#include "loopmix/loop_mix_renderer.h"

using bitchord::loopmix::Deck;
using bitchord::loopmix::Envelope;
using bitchord::loopmix::Renderer;

namespace {

Renderer* From(jlong handle) { return reinterpret_cast<Renderer*>(handle); }

Deck* DeckOf(jlong handle, jint index) {
  if (handle == 0 || index < 0 || index > 1) return nullptr;
  return &From(handle)->deck(index);
}

// Setup allocates; a failure becomes an OutOfMemoryError the caller's
// runCatching turns into an ordinary blend, rather than a C++ exception
// crossing into the VM.
void ThrowOutOfMemory(JNIEnv* env) {
  jclass error = env->FindClass("java/lang/OutOfMemoryError");
  if (error != nullptr) env->ThrowNew(error, "loop mix renderer");
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeCreate(JNIEnv*, jclass, jint sampleRate) {
  try {
    return reinterpret_cast<jlong>(new (std::nothrow) Renderer(sampleRate));
  } catch (const std::bad_alloc&) {
    return 0;
  }
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeDestroy(JNIEnv*, jclass, jlong handle) {
  delete From(handle);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetDeck(
    JNIEnv* env, jclass, jlong handle, jint index, jfloatArray left, jfloatArray right,
    jdouble regionStart, jlong loopStart, jlong loopEnd, jdouble releaseAt,
    jdoubleArray knotFrames, jdoubleArray knotPositions, jboolean runOn, jdouble stopAt,
    jdouble preroll, jfloat level) {
  Deck* deck = DeckOf(handle, index);
  if (deck == nullptr) return;
  const jsize frames = std::min(env->GetArrayLength(left), env->GetArrayLength(right));
  const jsize knots = std::min(env->GetArrayLength(knotFrames), env->GetArrayLength(knotPositions));
  try {
    deck->left.resize(frames);
    deck->right.resize(frames);
    deck->knotTimes.resize(knots);
    deck->knotPositions.resize(knots);
  } catch (const std::bad_alloc&) {
    ThrowOutOfMemory(env);
    return;
  }
  env->GetFloatArrayRegion(left, 0, frames, deck->left.data());
  env->GetFloatArrayRegion(right, 0, frames, deck->right.data());
  deck->regionStart = regionStart;
  deck->loopStart = loopStart;
  deck->loopEnd = loopEnd;
  deck->releaseAt = releaseAt;
  env->GetDoubleArrayRegion(knotFrames, 0, knots, deck->knotTimes.data());
  env->GetDoubleArrayRegion(knotPositions, 0, knots, deck->knotPositions.data());
  deck->runOn = runOn;
  deck->stopAt = stopAt;
  deck->preroll = preroll;
  deck->level = level;
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetEnvelope(
    JNIEnv* env, jclass, jlong handle, jint index, jint kind, jdoubleArray times, jfloatArray values,
    jboolean exponential) {
  Deck* deck = DeckOf(handle, index);
  if (deck == nullptr) return;
  Envelope* envelope = kind == 0 ? &deck->gain : kind == 1 ? &deck->lowDb : kind == 2 ? &deck->highPassHz : nullptr;
  if (envelope == nullptr) return;
  const jsize points = std::min(env->GetArrayLength(times), env->GetArrayLength(values));
  try {
    envelope->times.resize(points);
    envelope->values.resize(points);
  } catch (const std::bad_alloc&) {
    ThrowOutOfMemory(env);
    return;
  }
  env->GetDoubleArrayRegion(times, 0, points, envelope->times.data());
  env->GetFloatArrayRegion(values, 0, points, envelope->values.data());
  envelope->exponential = exponential;
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativePrepare(JNIEnv* env, jclass, jlong handle,
                                                                       jint maxFrames) {
  if (handle == 0) return;
  try {
    From(handle)->prepare(maxFrames);
  } catch (const std::bad_alloc&) {
    ThrowOutOfMemory(env);
  }
}

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeFirstFrame(JNIEnv*, jclass, jlong handle) {
  return handle != 0 ? From(handle)->firstFrame() : 0;
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeRender(
    JNIEnv* env, jclass, jlong handle, jlong frame, jfloatArray interleaved, jint offsetFrames, jint frames) {
  if (handle == 0 || frames <= 0) return;
  // Critical access: no copy of the block, and render() itself never calls back into Java.
  auto* samples = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(interleaved, nullptr));
  if (samples == nullptr) return;
  // [prepare] sized every buffer for the sink's blocks, so this never
  // allocates; should a larger block ever come, a failed allocation is a
  // silent block, and the critical array is always released.
  try {
    From(handle)->render(frame, samples + 2 * offsetFrames, frames);
  } catch (const std::bad_alloc&) {
    std::fill(samples + 2 * offsetFrames, samples + 2 * (offsetFrames + frames), 0.f);
  }
  env->ReleasePrimitiveArrayCritical(interleaved, samples, 0);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetFadeOut(JNIEnv*, jclass, jlong handle, jint index,
                                                                        jdouble trackSeconds) {
  Deck* deck = DeckOf(handle, index);
  if (deck == nullptr) return;
  deck->fadeOutAt.store(trackSeconds < 0 ? -1.0
                                         : deck->releasedPositionOf(trackSeconds, From(handle)->sampleRate()));
}

JNIEXPORT jdouble JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeHeard(JNIEnv*, jclass, jlong handle, jint index) {
  Deck* deck = DeckOf(handle, index);
  return deck != nullptr ? deck->heard.load() : 0.0;
}

}  // extern "C"
