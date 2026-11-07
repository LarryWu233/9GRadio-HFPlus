package xdsopl.robot36;

import java.io.InputStream;
import java.io.IOException;
import java.util.Arrays;

/** Application adapter; the Robot36 decoder and PCM readers are unmodified. Single worker owner. */
public final class RadioSstvBridge {
    private final PixelBuffer image = new PixelBuffer(800, 616);
    private final Decoder decoder;
    public RadioSstvBridge(int sampleRate, String mode) {
        decoder = new Decoder(new PixelBuffer(640, 2560), image, "Raw", sampleRate);
        decoder.setMode(mode);
    }
    public void process(float[] samples) { decoder.process(samples, 0); }
    public void finish() { decoder.finish(0); }
    public int getLines() { return Math.max(0, image.line); }
    public int getWidth() { return image.width; }
    public int getHeight() { return image.height; }
    public String getMode() { return decoder.currentMode.getName(); }
    public boolean isComplete() { return image.line == image.height; }
    public int[] copyPixels() {
        int[] result = Arrays.copyOf(image.pixels, image.width * image.height);
        if (image.line >= 0 && image.line < image.height)
            Arrays.fill(result, image.line * image.width, result.length, 0xff000000);
        return result;
    }
    public void acknowledgeComplete() { if (isComplete()) image.line = -1; }
    public static AudioSource wav(InputStream input, boolean turbo) {
        return new FileAudioSource(() -> WavFileReader.open(input), turbo);
    }
}
