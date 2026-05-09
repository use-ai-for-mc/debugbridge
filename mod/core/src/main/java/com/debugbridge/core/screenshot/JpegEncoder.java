package com.debugbridge.core.screenshot;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * JPEG encoder shared by all fabric shims. Takes raw ARGB pixels (alpha is
 * discarded by JPEG) and writes a quality-controlled JPEG to a temp file.
 * <p>
 * Lives in core because it depends only on the JDK ({@link ImageIO} via the
 * {@code java.desktop} module, which is part of every standard JRE).
 */
public final class JpegEncoder {
    private JpegEncoder() {
    }

    /**
     * Write the given ARGB pixels as a JPEG to a fresh temp file.
     *
     * @param argbPixels pixels in row-major order, 0xAARRGGBB layout. Length must be width * height.
     * @param width      image width
     * @param height     image height
     * @param quality    JPEG quality in [0.0, 1.0]; clamped to [0.05, 1.0]
     * @return the absolute path of the written file
     */
    public static Path writeJpegTempFile(int[] argbPixels, int width, int height, float quality)
            throws IOException {
        if (argbPixels.length != width * height) {
            throw new IllegalArgumentException(
                    "pixel buffer length " + argbPixels.length + " != " + width + "*" + height);
        }
        float clamped = Math.max(0.05f, Math.min(quality, 1.0f));

        // TYPE_INT_RGB drops the alpha byte when we setRGB with ARGB ints,
        // which is exactly what we want for JPEG (no alpha channel).
        BufferedImage bi = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        bi.setRGB(0, 0, width, height, argbPixels, 0, width);

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG ImageWriter available in this JRE");
        }
        ImageWriter writer = writers.next();

        Path file = Files.createTempFile("debugbridge-screenshot-", ".jpg");
        try (FileImageOutputStream out = new FileImageOutputStream(file.toFile())) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(clamped);
            writer.write(null, new IIOImage(bi, null, null), param);
        } finally {
            writer.dispose();
        }
        return file.toAbsolutePath();
    }
}
