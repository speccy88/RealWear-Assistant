package com.fred.realweargpt;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import static org.junit.Assert.*;

public class PcmResamplerTest {
    @Test public void arbitraryChunkBoundariesProduceIdenticalAudio() {
        short[] source = new short[10009];
        Random random = new Random(817);
        for (int i = 0; i < source.length; i++) source[i] = (short) (random.nextInt(16001) - 8000);
        for (int[] rate : new int[][] { {48000,24000}, {16000,24000}, {24000,48000}, {24000,16000} }) {
            PcmResampler whole = new PcmResampler(rate[0], rate[1]);
            short[] expected = whole.process(source, source.length);
            PcmResampler split = new PcmResampler(rate[0], rate[1]);
            ByteArrayOutputStream actual = new ByteArrayOutputStream();
            int offset = 0;
            while (offset < source.length) {
                int count = Math.min(source.length - offset, 1 + random.nextInt(477));
                byte[] pcm = PcmResampler.littleEndian(split.process(source, offset, count));
                actual.write(pcm, 0, pcm.length);
                offset += count;
            }
            assertArrayEquals(PcmResampler.littleEndian(expected), actual.toByteArray());
            assertEquals(((long) source.length * rate[1] + rate[0] - 1) / rate[0], expected.length);
        }
    }

    @Test public void lowPassFilterSuppressesUltrasoundBeforeDecimation() {
        short[] speechBand = sine(48000, 1000, 48000, 12000);
        short[] aliasBand = sine(48000, 18000, 48000, 12000);
        short[] speech = new PcmResampler(48000, 24000).process(speechBand, speechBand.length);
        short[] alias = new PcmResampler(48000, 24000).process(aliasBand, aliasBand.length);
        double speechRms = rms(speech, 128);
        double aliasRms = rms(alias, 128);
        assertTrue("Speech band amplitude must survive", speechRms > 8000 && speechRms < 8800);
        assertTrue("Prevent > 30 dB of 18 kHz aliasing", aliasRms < speechRms / 32);
    }

    @Test public void twentyMillisecondNativeBlocksYieldTwentyMillisecondApiBlocks() {
        for (int inputRate : new int[] { 48000, 16000 }) {
            PcmResampler converter = new PcmResampler(inputRate, 24000);
            short[] input = new short[inputRate / 50];
            for (int block = 0; block < 100; block++)
                assertEquals(480, converter.process(input, input.length).length);
        }
    }

    @Test public void signedPcmIsLittleEndianAndLossless() {
        short[] values = { Short.MIN_VALUE, -12345, -1, 0, 1, 12345, Short.MAX_VALUE };
        byte[] bytes = PcmResampler.littleEndian(values);
        assertEquals((byte) 0x00, bytes[0]);
        assertEquals((byte) 0x80, bytes[1]);
        assertEquals((byte) 0xff, bytes[bytes.length - 2]);
        assertEquals((byte) 0x7f, bytes[bytes.length - 1]);
        assertArrayEquals(values, PcmResampler.samples(bytes));
    }

    @Test public void resetDropsPriorAudioAtAnInterruption() {
        PcmResampler converter = new PcmResampler(24000, 48000);
        short[] first = sine(24000, 400, 731, 20000);
        converter.process(first, first.length);
        converter.reset();
        short[] afterReset = converter.process(new short[480], 480);
        assertArrayEquals(new short[960], afterReset);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsHalfPcmSamples() { PcmResampler.samples(new byte[3]); }

    private static short[] sine(int sampleRate, int frequency, int length, int amplitude) {
        short[] output = new short[length];
        for (int i = 0; i < length; i++)
            output[i] = (short) Math.round(amplitude * Math.sin(2 * Math.PI * frequency * i / sampleRate));
        return output;
    }

    private static double rms(short[] samples, int skip) {
        double squared = 0;
        for (int i = skip; i < samples.length; i++) squared += (double) samples[i] * samples[i];
        return Math.sqrt(squared / (samples.length - skip));
    }
}
