package com.seungjae.jangsu280battery;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class ReleaseZipPackage {
    static final long MAX_FILE_BYTES = 10L * 1024L * 1024L;
    static final long MAX_TOTAL_BYTES = 30L * 1024L * 1024L;
    static final int MAX_FILES = 700;

    final Map<String, byte[]> files;
    final String version;
    final String strippedRoot;
    final long totalBytes;

    private ReleaseZipPackage(Map<String, byte[]> files, String version, String strippedRoot, long totalBytes) {
        this.files = files;
        this.version = version;
        this.strippedRoot = strippedRoot;
        this.totalBytes = totalBytes;
    }

    static ReleaseZipPackage read(ContentResolver resolver, Uri uri) throws IOException {
        InputStream opened = resolver.openInputStream(uri);
        if (opened == null) throw new IOException("ZIP을 열 수 없습니다.");
        return read(opened);
    }

    static ReleaseZipPackage read(File file) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("백그라운드 배포 ZIP을 찾을 수 없습니다.");
        return read(new FileInputStream(file));
    }

    private static ReleaseZipPackage read(InputStream opened) throws IOException {
        Map<String, byte[]> raw = new LinkedHashMap<>();
        long total = 0L;
        try (InputStream in = opened; ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = normalize(entry.getName());
                if (name.trim().isEmpty() || shouldIgnore(name)) continue;
                if (raw.size() >= MAX_FILES) throw new IOException("파일 수가 너무 많습니다. 최대 " + MAX_FILES + "개");
                byte[] bytes = readEntry(zis, MAX_FILE_BYTES);
                total += bytes.length;
                if (total > MAX_TOTAL_BYTES) throw new IOException("ZIP 소스가 너무 큽니다. 최대 30MB");
                raw.put(name, bytes);
            }
        }
        if (raw.isEmpty()) throw new IOException("ZIP 안에 업로드할 파일이 없습니다.");

        String root = detectSingleRoot(raw.keySet());
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : raw.entrySet()) {
            String path = e.getKey();
            if (!root.trim().isEmpty() && path.startsWith(root + "/")) path = path.substring(root.length() + 1);
            path = normalize(path);
            if (path.trim().isEmpty() || shouldIgnore(path)) continue;
            files.put(path, e.getValue());
        }

        validate(files);
        String version = text(files.get("VERSION.txt")).trim();
        if (version.trim().isEmpty()) {
            version = parseVersionFromGradle(text(files.get("app/build.gradle.kts")));
        }
        if (version.trim().isEmpty()) throw new IOException("VERSION.txt 또는 versionName을 찾지 못했습니다.");
        if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-._A-Za-z0-9]+)?")) {
            throw new IOException("버전 형식이 이상합니다: " + version);
        }
        return new ReleaseZipPackage(Collections.unmodifiableMap(files), version, root, total);
    }

    private static void validate(Map<String, byte[]> files) throws IOException {
        List<String> required = java.util.Arrays.asList(
                "VERSION.txt",
                "settings.gradle.kts",
                "build.gradle.kts",
                "app/build.gradle.kts",
                "app/src/main/AndroidManifest.xml"
        );
        List<String> missing = new ArrayList<>();
        for (String r : required) if (!files.containsKey(r)) missing.add(r);
        if (!missing.isEmpty()) throw new IOException("BatteryPilot 전체 소스 ZIP이 아닙니다. 누락: " + String.join(", ", missing));
        if (!files.keySet().stream().anyMatch(p -> p.startsWith("app/src/main/java/") || p.startsWith("app/src/main/kotlin/"))) {
            throw new IOException("Android 소스 코드(app/src/main/java 또는 kotlin)가 없습니다.");
        }
    }

    private static String parseVersionFromGradle(String gradle) {
        Matcher m = Pattern.compile("versionName\\s*=\\s*[\\\"]([^\\\"]+)[\\\"]").matcher(gradle);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String detectSingleRoot(Set<String> paths) {
        Set<String> roots = new HashSet<>();
        boolean hasRootFile = false;
        for (String p : paths) {
            int slash = p.indexOf('/');
            if (slash < 0) {
                hasRootFile = true;
                break;
            }
            roots.add(p.substring(0, slash));
            if (roots.size() > 1) break;
        }
        return (!hasRootFile && roots.size() == 1) ? roots.iterator().next() : "";
    }

    private static boolean shouldIgnore(String p) {
        String lower = p.toLowerCase();
        return lower.startsWith(".git/")
                || lower.startsWith(".gradle/")
                || lower.startsWith(".idea/")
                || lower.contains("/build/")
                || lower.endsWith("/local.properties")
                || lower.equals("local.properties")
                || lower.endsWith(".jks")
                || lower.endsWith(".keystore")
                || lower.endsWith(".apk")
                || lower.endsWith(".aab")
                || lower.endsWith(".DS_Store".toLowerCase());
    }

    private static String normalize(String p) {
        p = p.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        while (p.contains("//")) p = p.replace("//", "/");
        if (p.contains("../") || p.equals("..")) return "";
        return p;
    }

    private static byte[] readEntry(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        long count = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            count += n;
            if (count > max) throw new IOException("단일 파일이 10MB를 초과합니다.");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String text(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }
}
