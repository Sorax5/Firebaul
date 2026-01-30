package fr.phylisium.firebaul.keyword;

import de.maxhenkel.opus4j.OpusDecoder;
import fr.phylisium.firebaul.Firebaul;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Encapsule le décodage Opus et le Recognizer Vosk pour faciliter les tests/mocks.
 */
public class AudioRecognizer {
    private final OpusDecoder decoder;
    private final Recognizer recognizer;

    public AudioRecognizer(Model model) throws Exception {
        this.decoder = new OpusDecoder(48000, 1);
        this.decoder.setFrameSize(960);
        this.recognizer = new Recognizer(model, 16000.0f);
    }

    /**
     * Decode an opus frame into 16k little-endian PCM bytes (mono).
     * Returns null if decoding failed or no audio.
     */
    public byte[] decodeOpusTo16kBytes(byte[] opus) {
        if (opus == null || opus.length == 0) {
            return null;
        }
        short[] decoded = decoder.decode(opus);
        if (decoded == null || decoded.length == 0) {
            return null;
        }
        int outLen = decoded.length / 3; // simple downsample by taking every 3rd sample
        if (outLen <= 0) {
            return null;
        }
        short[] pcm16k = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            pcm16k[i] = decoded[i * 3];
        }
        return shortsToLittleEndianBytes(pcm16k, outLen);
    }

    public boolean acceptWaveForm(byte[] pcmBytes, int len) {
        try {
            return recognizer.acceptWaveForm(pcmBytes, len);
        } catch (Exception e) {
            Firebaul.getInstance().getLogger().warning("Error while feeding recognizer: " + e.getMessage());
            return false;
        }
    }

    public String getPartialResult() {
        try {
            return recognizer.getPartialResult();
        } catch (Exception e) {
            Firebaul.getInstance().getLogger().warning("Unable to read partial result: " + e.getMessage());
            return null;
        }
    }

    public String getFinalResult() {
        try {
            return recognizer.getFinalResult();
        } catch (Exception e) {
            Firebaul.getInstance().getLogger().warning("Unable to read final result: " + e.getMessage());
            return null;
        }
    }

    public void reset() {
        try {
            decoder.resetState();
        } catch (Exception ignored) {
        }
        try {
            recognizer.reset();
        } catch (Exception ignored) {
        }
    }

    public void close() {
        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (Exception ignored) {
            }
        }
        try {
            decoder.resetState();
        } catch (Exception ignored) {
        }
        try {
            decoder.close();
        } catch (Exception ignored) {
        }
    }

    private byte[] shortsToLittleEndianBytes(short[] shorts, int len) {
        if (shorts == null || len <= 0) {
            return null;
        }
        byte[] bytes = new byte[len * 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts, 0, len);
        return bytes;
    }
}
