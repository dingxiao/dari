package com.psddev.dari.util;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** IO utility methods. */
public final class IoUtils {

    private static final int BUFFER_SIZE = 0x1000;
    private static final Logger LOGGER = LoggerFactory.getLogger(IoUtils.class);

    private IoUtils() {
    }

    /**
     * Closes the given {@code closeable}.
     *
     * @param closeable If {@code null}, does nothing.
     * @param suppressError If {@code true}, logs the error instead of throwing it.
     */
    public static void close(Closeable closeable, boolean suppressError) throws IOException {
        if (closeable == null) {
            return;
        }

        try {
            closeable.close();
        } catch (IOException error) {
            if (suppressError) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("Can't close [" + closeable + "]!", error);
                }
            } else {
                throw error;
            }
        }
    }

    /**
     * Closes the given {@code closeable}, logging any errors that occur
     * instead of throwing them.
     *
     * @param closeable If {@code null}, does nothing.
     */
    public static void closeQuietly(Closeable closeable) {
        try {
            close(closeable, true);
        } catch (IOException error) {
        }
    }

    /**
     * Copies from the given {@code source} to the given {@code destination}.
     * Doesn't close either streams.
     *
     * @param source Can't be {@code null}.
     * @param destination Can't be {@code null}.
     * @return Number of bytes copied.
     */
    public static long copy(InputStream source, OutputStream destination) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0L;
        for (int read; (read = source.read(buffer)) > -1; ) {
            destination.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    /**
     * Copies the given {@code source} to the given {@code destination}.
     *
     * @param source Can't be {@code null}.
     * @param destination Can't be {@code null}.
     * @return Number of bytes copied.
     */
    public static long copy(File source, File destination) throws IOException {
        if (!destination.exists()) {
            File parent = destination.getParentFile();
            if (!parent.exists()) {
                parent.mkdirs();
            }
            destination.createNewFile();
        }

        FileInputStream sourceInput = new FileInputStream(source);
        long total = -1L;

        try {
            FileChannel sourceChannel = sourceInput.getChannel();
            total = sourceChannel.size();
            FileOutputStream destinationOutput = new FileOutputStream(destination);

            try {
                sourceChannel.transferTo(0, total, destinationOutput.getChannel());

            } finally {
                close(destinationOutput, true);
            }

        } finally {
            closeQuietly(sourceInput);
        }

        return total;
    }

    /**
     * Returns all bytes from the given {@code input}. Doesn't close
     * the stream.
     *
     * @param input Can't be {@code null}.
     * @return Never {@code null}.
     */
    public static byte[] toByteArray(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(input, output);
        return output.toByteArray();
    }

    /**
     * Returns all bytes from the given {@code file}.
     *
     * @param file Can't be {@code null}.
     * @return Never {@code null}.
     */
    public static byte[] toByteArray(File file) throws IOException {
        InputStream input = new FileInputStream(file);
        try {
            return toByteArray(input);
        } finally {
            closeQuietly(input);
        }
    }

    /**
     * Returns all bytes from the given {@code url}.
     *
     * @param url Can't be {@code null}.
     * @return Never {@code null}.
     */
    public static byte[] toByteArray(URL url) throws IOException {
        InputStream input = url.openStream();
        try {
            return toByteArray(input);
        } finally {
            closeQuietly(input);
        }
    }

    /**
     * Returns a file equivalent to the given {@code url}.
     *
     * @param url Can be {@code null}.
     * @param charset Can't be {@code null}.
     * @return {@code null} if the given {@code url} is {@code null} or
     * doesn't point to a file.
     */
    public static File toFile(URL url, Charset charset) {
        if (url == null || !"file".equalsIgnoreCase(url.getProtocol())) {
            return null;
        }

        byte[] encoded = url.getFile().replace('/', File.separatorChar).getBytes(StringUtils.US_ASCII);
        int length = encoded.length;
        byte[] decoded = new byte[length];
        int decodedIndex = 0;

        for (int i = 0; i < length; ++ i) {
            byte letter = encoded[i];

            if (letter == '%') {
                ++ i;

                if (i < length) {
                    byte hex1 = HEX_TO_BYTE[encoded[i]];

                    if (hex1 >= 0) {
                        ++ i;

                        if (i < length) {
                            byte hex2 = HEX_TO_BYTE[encoded[i]];

                            if (hex2 >= 0) {
                                decoded[decodedIndex] = (byte) (hex1 << 4 | hex2);
                                ++ decodedIndex;
                                continue;
                            }
                        }

                        -- i;
                    }
                }

                -- i;
            }

            decoded[decodedIndex] = letter;
            ++ decodedIndex;
        }

        return new File(new String(decoded, charset));
    }

    private static final byte[] HEX_TO_BYTE;

    static {
        int length = Byte.MAX_VALUE - Byte.MIN_VALUE;
        HEX_TO_BYTE = new byte[length];

        for (int i = 0; i < length; ++ i) {
            HEX_TO_BYTE[i] = -1;
        }

        for (int i = 0; i < 0x10; ++ i) {
            HEX_TO_BYTE[Integer.toHexString(i).charAt(0)] = (byte) i;
        }
    }

    /**
     * Reads all bytes from the given {@code input} and converts them
     * into a string using the given {@code charset}.
     *
     * @param input Can't be {@code null}.
     * @param charset Can't be {@code null}.
     * @return Never {@code null}.
     */
    public static String toString(InputStream input, Charset charset) throws IOException {
        return new String(toByteArray(input), charset);
    }

    /**
     * Reads all bytes from the given {@code file} and converts them
     * into a string using the given {@code charset}.
     *
     * @param file Can't be {@code null}.
     * @param charset Can't be {@code null}.
     * @return Never {@code null}.
     */
    public static String toString(File file, Charset charset) throws IOException {
        return new String(toByteArray(file), charset);
    }

    /**
     * Reads all bytes from the given {@code url} and converts them
     * into a string using the response content encoding. If that encoding
     * isn't a valid Java charset, uses {@link StringUtils#UTF_8} instead.
     *
     * @param url Can't be {@code null}.
     * @return Never {@code null}.
     */
    public static String toString(URL url) throws IOException {
        URLConnection connection = url.openConnection();
        InputStream input = connection.getInputStream();

        try {
            Charset charset = null;

            try {
                charset = Charset.forName(connection.getContentEncoding());
            } catch (IllegalCharsetNameException error) {
            } catch (IllegalArgumentException error) {
            }

            if (charset == null) {
                charset = StringUtils.UTF_8;
            }

            return new String(toByteArray(input), charset);

        } finally {
            closeQuietly(input);
        }
    }
}
