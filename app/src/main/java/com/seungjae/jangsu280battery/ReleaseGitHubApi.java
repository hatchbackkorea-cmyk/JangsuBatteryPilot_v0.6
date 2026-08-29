package com.seungjae.jangsu280battery;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ReleaseGitHubApi {
    interface Progress {
        void onProgress(int percent, String message);
    }

    static final class RepoInfo {
        final String fullName;
        final String defaultBranch;
        final boolean isPrivate;

        RepoInfo(String fullName, String defaultBranch, boolean isPrivate) {
            this.fullName = fullName;
            this.defaultBranch = defaultBranch;
            this.isPrivate = isPrivate;
        }
    }

    static final class PushResult {
        final String commitSha;
        final String version;
        final int uploadedFiles;
        final int skippedFiles;
        final int deletedFiles;

        PushResult(String commitSha, String version, int uploadedFiles, int skippedFiles, int deletedFiles) {
            this.commitSha = commitSha;
            this.version = version;
            this.uploadedFiles = uploadedFiles;
            this.skippedFiles = skippedFiles;
            this.deletedFiles = deletedFiles;
        }
    }

    private static final class RemoteBlob {
        final String sha;
        final String mode;
        final String type;

        RemoteBlob(String sha, String mode, String type) {
            this.sha = sha == null ? "" : sha;
            this.mode = mode == null ? "" : mode;
            this.type = type == null ? "" : type;
        }
    }

    static final class ReleaseInfo {
        final long id;
        final String tag;
        final String htmlUrl;
        final long apkAssetId;
        final String apkName;

        ReleaseInfo(long id, String tag, String htmlUrl, long apkAssetId, String apkName) {
            this.id = id;
            this.tag = tag;
            this.htmlUrl = htmlUrl;
            this.apkAssetId = apkAssetId;
            this.apkName = apkName;
        }

        boolean hasApk() { return apkAssetId > 0 && apkName != null && !apkName.trim().isEmpty(); }
    }

    static final class ApiException extends IOException {
        final int status;
        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static final String API = "https://api.github.com";
    private final String token;

    ReleaseGitHubApi(String token) {
        this.token = token == null ? "" : token.trim();
    }

    RepoInfo getRepo(String repo) throws Exception {
        JSONObject obj = requestJson("GET", "/repos/" + repo, null, null);
        return new RepoInfo(
                obj.optString("full_name", repo),
                obj.optString("default_branch", "main"),
                obj.optBoolean("private", false)
        );
    }

    boolean releaseExists(String repo, String version) throws Exception {
        try {
            requestJson("GET", "/repos/" + repo + "/releases/tags/v" + version, null, null);
            return true;
        } catch (ApiException e) {
            if (e.status == 404) return false;
            throw e;
        }
    }

    PushResult pushZip(String repo, String branch, ReleaseZipPackage pkg, boolean updateWorkflows, Progress progress) throws Exception {
        String refPath = "/repos/" + repo + "/git/ref/heads/" + branch;
        progress.onProgress(2, "원격 main 기준점 확인");
        JSONObject ref = requestJson("GET", refPath, null, null);
        String parentCommitSha = ref.getJSONObject("object").getString("sha");

        JSONObject commit = requestJson("GET", "/repos/" + repo + "/git/commits/" + parentCommitSha, null, null);
        String baseTreeSha = commit.getJSONObject("tree").getString("sha");

        progress.onProgress(5, "원격 파일 목록 확인");
        JSONObject remoteTreeJson = requestJson("GET", "/repos/" + repo + "/git/trees/" + baseTreeSha + "?recursive=1", null, null);
        JSONArray remoteTree = remoteTreeJson.optJSONArray("tree");
        if (remoteTreeJson.optBoolean("truncated", false)) {
            throw new IOException("저장소 트리가 너무 커서 GitHub가 목록을 잘랐습니다. 모바일 업로더로 안전하게 교체할 수 없습니다.");
        }

        Set<String> newPaths = new HashSet<>();
        for (String p : pkg.files.keySet()) {
            if (!updateWorkflows && p.startsWith(".github/workflows/")) continue;
            newPaths.add(p);
        }

        // v0.29.4: 원격의 'leaf' 엔트리를 보존해 최종 트리를 통째로 재구성한다.
        // 기존 방식은 삭제 파일을 sha:null 엔트리로 base_tree에 얹었는데, 일부 저장소 상태에서
        // GitHub가 422 "Invalid tree info"를 반환할 수 있었다. 최종 leaf 집합을 명시하면
        // 변경 blob만 업로드하는 속도 이점은 유지하면서 삭제 null 엔트리를 피할 수 있다.
        Map<String, RemoteBlob> remoteLeaves = new HashMap<>();
        Map<String, RemoteBlob> remoteBlobs = new HashMap<>();
        if (remoteTree != null) {
            for (int i = 0; i < remoteTree.length(); i++) {
                JSONObject item = remoteTree.getJSONObject(i);
                String type = item.optString("type");
                if ("tree".equals(type)) continue;
                String path = item.optString("path");
                if (path == null || path.trim().isEmpty()) continue;
                RemoteBlob leaf = new RemoteBlob(item.optString("sha"), item.optString("mode"), type);
                remoteLeaves.put(path, leaf);
                if ("blob".equals(type)) remoteBlobs.put(path, leaf);
            }
        }

        List<Map.Entry<String, byte[]>> changedFiles = new ArrayList<>();
        int skipped = 0;
        for (Map.Entry<String, byte[]> e : pkg.files.entrySet()) {
            String path = e.getKey();
            if (!updateWorkflows && path.startsWith(".github/workflows/")) continue;
            String wantedMode = executableMode(path) ? "100755" : "100644";
            RemoteBlob remote = remoteBlobs.get(path);
            String localSha = gitBlobSha(e.getValue());
            if (remote != null && localSha.equalsIgnoreCase(remote.sha) && wantedMode.equals(remote.mode)) {
                skipped++;
            } else {
                changedFiles.add(e);
            }
        }

        int deletions = 0;
        for (String path : remoteLeaves.keySet()) {
            if (shouldDeleteStale(path, newPaths, updateWorkflows)) deletions++;
        }

        int totalUpload = changedFiles.size();
        progress.onProgress(7, String.format(Locale.KOREA,
                "변경분 계산 완료 · 업로드 %d · 동일 %d · 삭제 %d", totalUpload, skipped, deletions));

        Map<String, String> uploadedSha = new HashMap<>();
        int done = 0;
        for (Map.Entry<String, byte[]> e : changedFiles) {
            String path = e.getKey();
            int pct = 8 + (int) Math.round((done / (double) Math.max(1, totalUpload)) * 67.0);
            progress.onProgress(pct, String.format(Locale.KOREA, "변경 파일 업로드 %d/%d · %s", done + 1, totalUpload, shorten(path)));

            JSONObject blobBody = new JSONObject();
            blobBody.put("content", Base64.encodeToString(e.getValue(), Base64.NO_WRAP));
            blobBody.put("encoding", "base64");
            JSONObject blob = requestJson("POST", "/repos/" + repo + "/git/blobs", blobBody, null);
            uploadedSha.put(path, blob.getString("sha"));
            done++;
        }

        String newTreeSha = baseTreeSha;
        if (totalUpload > 0 || deletions > 0) {
            progress.onProgress(78, "안전한 최종 Git tree 생성");
            Map<String, JSONObject> finalLeaves = new HashMap<>();

            // 1) 앱이 관리하지 않는 기존 파일/워크플로는 그대로 보존한다.
            for (Map.Entry<String, RemoteBlob> e : remoteLeaves.entrySet()) {
                String path = e.getKey();
                if (shouldDeleteStale(path, newPaths, updateWorkflows)) continue;
                if (newPaths.contains(path)) continue; // 아래 로컬 소스로 덮어쓴다.
                RemoteBlob leaf = e.getValue();
                JSONObject te = new JSONObject();
                te.put("path", path);
                te.put("mode", leaf.mode);
                te.put("type", leaf.type);
                te.put("sha", leaf.sha);
                finalLeaves.put(path, te);
            }

            // 2) ZIP 안의 최종 프로젝트 파일을 넣는다. 동일 파일은 기존 blob SHA를 재사용한다.
            for (Map.Entry<String, byte[]> e : pkg.files.entrySet()) {
                String path = e.getKey();
                if (!updateWorkflows && path.startsWith(".github/workflows/")) continue;
                String wantedMode = executableMode(path) ? "100755" : "100644";
                RemoteBlob remote = remoteBlobs.get(path);
                String sha = uploadedSha.get(path);
                if (sha == null && remote != null && wantedMode.equals(remote.mode)
                        && gitBlobSha(e.getValue()).equalsIgnoreCase(remote.sha)) {
                    sha = remote.sha;
                }
                if (sha == null || sha.trim().isEmpty()) {
                    throw new IOException("최종 Git tree blob SHA를 만들지 못했습니다: " + path);
                }
                JSONObject te = new JSONObject();
                te.put("path", path);
                te.put("mode", wantedMode);
                te.put("type", "blob");
                te.put("sha", sha);
                finalLeaves.put(path, te);
            }

            JSONArray treeEntries = new JSONArray();
            List<String> finalPaths = new ArrayList<>(finalLeaves.keySet());
            java.util.Collections.sort(finalPaths);
            for (String path : finalPaths) treeEntries.put(finalLeaves.get(path));

            JSONObject treeBody = new JSONObject();
            treeBody.put("tree", treeEntries); // base_tree 없이 최종 트리 자체를 생성
            JSONObject newTree = requestJson("POST", "/repos/" + repo + "/git/trees", treeBody, null);
            newTreeSha = newTree.getString("sha");
        } else {
            progress.onProgress(78, "변경 파일 없음 · 기존 tree 재사용");
        }

        progress.onProgress(83, "릴리즈 커밋 생성");
        JSONObject commitBody = new JSONObject();
        commitBody.put("message", "Mobile release v" + pkg.version);
        commitBody.put("tree", newTreeSha);
        JSONArray parents = new JSONArray();
        parents.put(parentCommitSha);
        commitBody.put("parents", parents);
        JSONObject newCommit = requestJson("POST", "/repos/" + repo + "/git/commits", commitBody, null);
        String newCommitSha = newCommit.getString("sha");

        progress.onProgress(90, branch + " 브랜치 갱신");
        JSONObject patchBody = new JSONObject();
        patchBody.put("sha", newCommitSha);
        patchBody.put("force", false);
        requestJson("PATCH", "/repos/" + repo + "/git/refs/heads/" + branch, patchBody, null);

        progress.onProgress(100, "GitHub push 완료 · Actions 사인 Release 시작");
        return new PushResult(newCommitSha, pkg.version, done, skipped, deletions);
    }

    ReleaseInfo getRelease(String repo, String version) throws Exception {
        JSONObject obj = requestJson("GET", "/repos/" + repo + "/releases/tags/v" + version, null, null);
        long apkId = -1L;
        String apkName = null;
        JSONArray assets = obj.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String name = a.optString("name", "");
                if (name.toLowerCase(Locale.US).endsWith(".apk")) {
                    apkId = a.optLong("id", -1L);
                    apkName = name;
                    if (name.toLowerCase(Locale.US).contains("release")) break;
                }
            }
        }
        return new ReleaseInfo(
                obj.optLong("id", -1L),
                obj.optString("tag_name", "v" + version),
                obj.optString("html_url", ""),
                apkId,
                apkName
        );
    }

    ReleaseInfo waitForRelease(String repo, String version, long timeoutMs, Progress progress) throws Exception {
        long started = System.currentTimeMillis();
        int attempt = 0;
        while (System.currentTimeMillis() - started < timeoutMs) {
            attempt++;
            try {
                ReleaseInfo info = getRelease(repo, version);
                if (info.hasApk()) {
                    progress.onProgress(100, "사인 Release APK 생성 완료");
                    return info;
                }
            } catch (ApiException e) {
                if (e.status != 404) throw e;
            }
            long elapsed = System.currentTimeMillis() - started;
            int pct = Math.min(95, 5 + (int) ((elapsed / (double) timeoutMs) * 90));
            progress.onProgress(pct, "GitHub Actions 빌드 대기 · " + (elapsed / 1000) + "초");
            Thread.sleep(12_000L);
        }
        throw new IOException("아직 Release가 만들어지지 않았습니다. 빌드가 오래 걸리거나 Actions가 실패했을 수 있습니다. '사인 Release 상태 확인'을 다시 눌러주세요.");
    }

    File downloadReleaseApk(String repo, ReleaseInfo release, File outDir, Progress progress) throws Exception {
        if (!release.hasApk()) throw new IOException("Release에 APK 자산이 없습니다.");
        if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("APK 저장 폴더를 만들 수 없습니다.");
        File out = new File(outDir, safeFileName(release.apkName));
        String path = "/repos/" + repo + "/releases/assets/" + release.apkAssetId;
        downloadBinary(path, out, progress);
        return out;
    }

    private boolean shouldDeleteStale(String path, Set<String> newPaths, boolean updateWorkflows) {
        if (newPaths.contains(path)) return false;
        if (path.startsWith(".github/workflows/")) return false;
        if (path.startsWith("app/") || path.startsWith("gradle/")) return true;
        return path.equals("settings.gradle.kts")
                || path.equals("build.gradle.kts")
                || path.equals("gradle.properties")
                || path.equals("VERSION.txt")
                || path.equals("gradlew")
                || path.equals("gradlew.bat");
    }

    private static String gitBlobSha(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] header = ("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8);
            digest.update(header);
            digest.update(bytes);
            byte[] hash = digest.digest();
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) out.append(String.format(Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (GeneralSecurityException e) {
            throw new IOException("Git blob 해시 계산 실패", e);
        }
    }

    private static boolean executableMode(String path) {
        return path.equals("gradlew") || path.endsWith(".sh");
    }

    private static String shorten(String path) {
        if (path.length() <= 46) return path;
        return "…" + path.substring(path.length() - 45);
    }

    private static String safeFileName(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private JSONObject requestJson(String method, String path, JSONObject body, String accept) throws Exception {
        HttpURLConnection c = open(method, path, accept == null ? "application/vnd.github+json" : accept);
        if (body != null) {
            c.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
        }
        int status = c.getResponseCode();
        String text = readText(status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream());
        if (status < 200 || status >= 300) {
            String msg = extractError(text);
            throw new ApiException(status, "GitHub HTTP " + status + " · " + msg);
        }
        if (text == null || text.trim().isEmpty()) return new JSONObject();
        return new JSONObject(text);
    }

    private void downloadBinary(String path, File out, Progress progress) throws Exception {
        URL url = new URL(API + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20_000);
        c.setReadTimeout(60_000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/octet-stream");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", "BatteryPilotRelease/0.1.0");
        int status = c.getResponseCode();
        if (status < 200 || status >= 300) {
            String err = readText(c.getErrorStream());
            throw new ApiException(status, "APK 다운로드 실패 · " + extractError(err));
        }
        long length = c.getContentLengthLong();
        try (InputStream in = c.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[32 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
                done += n;
                if (length > 0) {
                    int pct = (int) Math.min(100, Math.round(done * 100.0 / length));
                    progress.onProgress(pct, "APK 다운로드 " + pct + "%");
                }
            }
        }
        progress.onProgress(100, "APK 다운로드 완료");
    }

    private HttpURLConnection open(String method, String path, String accept) throws IOException {
        URL url = new URL(API + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20_000);
        c.setReadTimeout(60_000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", "BatteryPilotRelease/0.1.0");
        return c;
    }

    private static String readText(InputStream in) throws IOException {
        if (in == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        }
    }

    private static String extractError(String text) {
        if (text == null || text.trim().isEmpty()) return "응답 내용 없음";
        try {
            JSONObject obj = new JSONObject(text);
            String msg = obj.optString("message", text.trim());
            JSONArray errors = obj.optJSONArray("errors");
            if (errors != null && errors.length() > 0) msg += " · " + errors.toString();
            return msg;
        } catch (JSONException ignored) {
            return text.trim().replace('\n', ' ');
        }
    }
}
