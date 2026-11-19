package com.fred.realweargpt;

import java.util.Arrays;

/**
 * Streaming mono PCM16 resampler with a 31-tap windowed-sinc low-pass filter.
 * Its state spans input blocks, so arbitrary network/AudioRecord chunk boundaries are safe.
 * A small causal filter delay avoids needing future samples (less than 1 ms at 24 kHz).
 */
public final class PcmResampler {
    private static final int TAPS = 31;
    private final int inputRate;
    private final int outputRate;
    private final int divisor;
    private final double[][] filters;
    private final short[] history = new short[TAPS];
    private int head;
    private long inputSamples;
    private long nextOutputNumerator;

    public PcmResampler(int inputRate, int outputRate) {
        if (inputRate <= 0 || outputRate <= 0) throw new IllegalArgumentException("Invalid rates");
        this.inputRate = inputRate;
        this.outputRate = outputRate;
        divisor = gcd(inputRate, outputRate);
        int phases = outputRate / divisor;
        if (phases > 1024) throw new IllegalArgumentException("Unsupported rate ratio");
        filters = new double[phases][TAPS];
        double cutoff = 0.45 * Math.min(1.0, (double) outputRate / inputRate);
        for (int phase = 0; phase < phases; phase++) {
            double fraction = (double) phase / phases;
            double sum = 0;
            for (int tap = 0; tap < TAPS; tap++) {
                double x = tap - (TAPS - 1) / 2.0 + fraction;
                double sinc = Math.abs(x) < 1e-12 ? 2 * cutoff
                        : Math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x);
                double window = 0.5 * (1 + Math.cos(Math.PI * x / ((TAPS + 1) / 2.0)));
                filters[phase][tap] = sinc * window;
                sum += filters[phase][tap];
            }
            for (int tap = 0; tap < TAPS; tap++) filters[phase][tap] /= sum;
        }
    }

    public short[] process(short[] input, int count) { return process(input, 0, count); }

    public short[] process(short[] input, int offset, int count) {
        if (input == null || offset < 0 || count < 0 || offset > input.length - count)
            throw new IllegalArgumentException("Invalid PCM range");
        if (count == 0) return new short[0];
        if (inputRate == outputRate) return Arrays.copyOfRange(input, offset, offset + count);
        int capacity = (int) (((long) count * outputRate + inputRate - 1) / inputRate) + 2;
        short[] output = new short[capacity];
        int written = 0;
        for (int i = offset; i < offset + count; i++) {
            history[head] = input[i];
            long endOfSample = (inputSamples + 1) * outputRate;
            while (nextOutputNumerator < endOfSample) {
                int phase = (int) ((nextOutputNumerator % outputRate) / divisor);
                double value = 0;
                int index = head;
                for (int tap = 0; tap < TAPS; tap++) {
                    value += history[index] * filters[phase][tap];
                    if (--index < 0) index = TAPS - 1;
                }
                output[written++] = (short) Math.max(Short.MIN_VALUE,
                        Math.min(Short.MAX_VALUE, Math.round(value)));
                nextOutputNumerator += inputRate;
            }
            if (++head == TAPS) head = 0;
            inputSamples++;
        }
        return written == output.length ? output : Arrays.copyOf(output, written);
    }

    public void reset() {
        Arrays.fill(history, (short) 0);
        head = 0;
        inputSamples = 0;
        nextOutputNumerator = 0;
    }

    public static byte[] littleEndian(short[] samples) {
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            bytes[i * 2] = (byte) (samples[i] & 0xff);
            bytes[i * 2 + 1] = (byte) ((samples[i] >>> 8) & 0xff);
        }
        return bytes;
    }

    public static short[] samples(byte[] bytes) {
        if (bytes == null || (bytes.length & 1) != 0)
            throw new IllegalArgumentException("PCM16 needs complete two-byte samples");
        short[] samples = new short[bytes.length / 2];
        for (int i = 0; i < samples.length; i++)
            samples[i] = (short) ((bytes[i * 2] & 0xff) | ((bytes[i * 2 + 1] & 0xff) << 8));
        return samples;
    }

    private static int gcd(int a, int b) {
        while (b != 0) { int rest = a % b; a = b; b = rest; }
        return a;
    }
}
