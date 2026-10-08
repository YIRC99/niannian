package dev.niannian.reminder;

public final class UpdatePolicyTest {
    public static void main(String[] args) {
        if (!UpdatePolicy.newer(34, 35) || UpdatePolicy.newer(34, 34) || UpdatePolicy.newer(34, 33)) throw new AssertionError("version comparison");
        if (!UpdatePolicy.validDownloadPath("/api/v1/updates/" + "a".repeat(48) + "/download")) throw new AssertionError("valid path");
        for (String path : new String[]{"https://evil.invalid/a.apk", "//evil.invalid", "/api/v1/updates/../download", "/admin/releases"}) {
            if (UpdatePolicy.validDownloadPath(path)) throw new AssertionError("unsafe download path");
        }
        if (!UpdatePolicy.validDigest("a".repeat(64)) || UpdatePolicy.validDigest("x".repeat(64))) throw new AssertionError("digest");
        System.out.println("UpdatePolicyTest passed");
    }
}
