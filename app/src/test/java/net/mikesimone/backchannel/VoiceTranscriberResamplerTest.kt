package net.mikesimone.backchannel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class VoiceTranscriberResamplerTest {

  private fun tone(rate: Int, seconds: Double, hz: Double = 440.0): ShortArray {
    return ShortArray((rate * seconds).toInt()) { (10000 * sin(2 * PI * hz * it / rate)).roundToInt().toShort() }
  }

  /** Feeds [input] in [chunk]-sized pieces, as the decoder does. */
  private fun resampleInChunks(input: ShortArray, rate: Int, chunk: Int): ShortArray {
    val resampler = VoiceTranscriber.Resampler(16000)
    val out = ArrayList<Short>()
    var i = 0
    while (i < input.size) {
      out += resampler.process(input.copyOfRange(i, minOf(input.size, i + chunk)), rate).toList()
      i += chunk
    }
    return out.toShortArray()
  }

  @Test
  fun keepsTheDurationAt48k() {
    val out = resampleInChunks(tone(48000, 2.0), 48000, 1024)
    assertTrue("got ${out.size}", abs(out.size - 32000) <= 2)
  }

  @Test
  fun keepsTheDurationAt44k() {
    val out = resampleInChunks(tone(44100, 2.0), 44100, 2048)
    assertTrue("got ${out.size}", abs(out.size - 32000) <= 2)
  }

  @Test
  fun chunkingDoesNotChangeTheOutput() {
    val input = tone(44100, 0.5)
    val whole = resampleInChunks(input, 44100, input.size)
    val chunked = resampleInChunks(input, 44100, 333)
    assertEquals(whole.size, chunked.size)
    for (i in whole.indices) assertTrue("sample $i: ${whole[i]} vs ${chunked[i]}", abs(whole[i] - chunked[i]) <= 1)
  }

  @Test
  fun matchesTheToneAfterResampling() {
    val out = resampleInChunks(tone(48000, 1.0), 48000, 1024)
    val expected = tone(16000, 1.0)
    for (i in 0 until minOf(out.size, expected.size)) assertTrue("sample $i", abs(out[i] - expected[i]) <= 60)
  }

  @Test
  fun passesThroughAtTheTargetRate() {
    val input = shortArrayOf(1, 2, 3)
    assertArrayEquals(input, VoiceTranscriber.Resampler(16000).process(input, 16000))
  }

  @Test
  fun downmixAveragesChannels() {
    assertArrayEquals(shortArrayOf(15, -5), VoiceTranscriber.downmix(shortArrayOf(10, 20, -10, 0), 2))
    assertArrayEquals(shortArrayOf(7), VoiceTranscriber.downmix(shortArrayOf(7), 1))
  }
}
