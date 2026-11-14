package com.github.catvod.spider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Content-addressed executables leave the running image untouched during upgrades. */
final class GoProxyBinaryCache {
    private GoProxyBinaryCache() {}

    static File extract(File directory, String name, InputStream asset) throws IOException {
        if (asset == null) throw new IOException("Missing Go proxy asset: " + name);
        File temporary = null;
        try (InputStream input = asset) {
            temporary = File.createTempFile(name + "-", ".tmp", directory);
            MessageDigest digest = sha256();
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (temporary.length() == 0) throw new IOException("Empty Go proxy asset");
            String hash = hex(digest.digest());
            File target = new File(directory, name + "-" + hash);
            if (target.exists()) {
                if (!hash.equals(hash(target))) throw new IOException("Corrupt Go proxy cache: " + name);
                return target;
            }
            if (!temporary.setExecutable(true, true)) throw new IOException("Cannot make Go proxy executable");
            if (!temporary.renameTo(target)) throw new IOException("Cannot install Go proxy cache");
            return target;
        } finally {
            if (temporary != null && temporary.exists()) temporary.delete();
        }
    }

    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    private static String hash(File file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            result[i * 2] = digits[(bytes[i] & 255) >>> 4];
            result[i * 2 + 1] = digits[bytes[i] & 15];
        }
        return new String(result);
    }
}
