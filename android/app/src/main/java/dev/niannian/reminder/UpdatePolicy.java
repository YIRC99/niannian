package dev.niannian.reminder;

final class UpdatePolicy {
    static boolean newer(long installed, long available) { return available > installed && installed > 0; }
    static boolean validDownloadPath(String path) { return path != null && path.matches("/api/v1/updates/[a-f0-9]{48}/download"); }
    static boolean validDigest(String digest) { return digest != null && digest.matches("[a-f0-9]{64}"); }
}
