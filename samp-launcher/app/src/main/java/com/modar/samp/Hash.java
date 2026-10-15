package com.modar.samp;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/** Контрольные суммы SHA-256: проверка скачанных модов и файлов сборки. */
public final class Hash {

    private Hash() {
    }

    public static String sha256(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            return sha256(in);
        } finally {
            in.close();
        }
    }

    public static String sha256(InputStream in) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    /**
     * Копирует поток в файл и попутно считает SHA-256 — так большой мод не приходится
     * читать дважды. progress получает (скачано байт, всего байт), всего может быть -1.
     */
    public static String copyAndHash(InputStream in, OutputStream out, long total, Progress progress) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[64 * 1024];
        long done = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
            digest.update(buffer, 0, read);
            done += read;
            if (progress != null) {
                progress.onProgress(done, total);
            }
        }
        out.flush();
        return hex(digest.digest());
    }

    /** true, если хеш файла совпадает с ожидаемым (регистр и пробелы не важны). */
    public static boolean matches(File file, String expected) {
        if (expected == null || expected.trim().isEmpty()) {
            return true;
        }
        try {
            return expected.trim().equalsIgnoreCase(sha256(file));
        } catch (Throwable t) {
            return false;
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("В этой сборке Java нет SHA-256", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    /** Колбэк прогресса скачивания. */
    public interface Progress {
        void onProgress(long done, long total);
    }
}
