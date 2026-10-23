package com.pixelreplica.capture;

/** Quality is a measurement, never a claim that all surfaces have been captured. */
public record CaptureQuality(double sharpness, double darkFraction, double brightFraction) {
    public boolean useful() { return sharpness >= 45 && brightFraction < .08 && darkFraction < .5; }

    /** Evaluates the centre 70 percent; row and pixel strides need not be tightly packed. */
    public static CaptureQuality analyze(byte[] y, int width, int height, int rowStride, int pixelStride) {
        if (width < 8 || height < 8 || y.length < rowStride * (height - 1) + (width - 1) * pixelStride + 1)
            throw new IllegalArgumentException("Invalid luminance plane");
        double sum = 0, square = 0;
        int count = 0, dark = 0, bright = 0;
        for (int row = height * 15 / 100 + 1; row < height * 85 / 100 - 1; row += 2) {
            for (int col = width * 15 / 100 + 1; col < width * 85 / 100 - 1; col += 2) {
                int i = row * rowStride + col * pixelStride;
                int c = y[i] & 255;
                int lap = (y[i - pixelStride] & 255) + (y[i + pixelStride] & 255)
                        + (y[i - rowStride] & 255) + (y[i + rowStride] & 255) - 4 * c;
                sum += lap; square += (double) lap * lap;
                dark += c <= 8 ? 1 : 0; bright += c >= 247 ? 1 : 0; count++;
            }
        }
        return new CaptureQuality(square / count - Math.pow(sum / count, 2),
                (double) dark / count, (double) bright / count);
    }
}
