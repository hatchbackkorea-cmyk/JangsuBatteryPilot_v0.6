package com.seungjae.jangsu280battery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.text.DecimalFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReleaseUploaderActivity extends Activity {
    private static final int REQ_PICK_ZIP = 1001;
    private static final long RELEASE_WAIT_MS = 12L * 60L * 1000L;

    private EditText etRepo;
    private EditText etBranch;
    private EditText etToken;
    private CheckBox cbUpdateWorkflows;
    private Button btnTest;
    private Button btnPick;
    private Button btnDeploy;
    private Button btnCheckRelease;
    private Button btnInstall;
    private ProgressBar progress;
    private TextView tvZip;
    private TextView tvStatus;
    private TextView tvLog;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ReleaseSecureTokenStore secureTokenStore;
    private SharedPreferences prefs;
    private Uri selectedZipUri;
    private String selectedZipName = "";
    private ReleaseZipPackage selectedPackage;
    private ReleaseGitHubApi.ReleaseInfo latestRelease;
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_release_uploader);

        etRepo = findViewById(R.id.etRepo);
        etBranch = findViewById(R.id.etBranch);
        etToken = findViewById(R.id.etToken);
        cbUpdateWorkflows = findViewById(R.id.cbUpdateWorkflows);
        btnTest = findViewById(R.id.btnTest);
        btnPick = findViewById(R.id.btnPick);
        btnDeploy = findViewById(R.id.btnDeploy);
        btnCheckRelease = findViewById(R.id.btnCheckRelease);
        btnInstall = findViewById(R.id.btnInstall);
        progress = findViewById(R.id.progress);
        tvZip = findViewById(R.id.tvZip);
        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);

        secureTokenStore = new ReleaseSecureTokenStore(this);
        prefs = getSharedPreferences("release_settings", MODE_PRIVATE);
        etRepo.setText(prefs.getString("repo", BuildConfig.UPDATE_REPOSITORY == null ? "" : BuildConfig.UPDATE_REPOSITORY.trim()));
        etBranch.setText(prefs.getString("branch", "main"));
        etToken.setText(secureTokenStore.loadToken());
        cbUpdateWorkflows.setChecked(prefs.getBoolean("update_workflows", false));

        btnTest.setOnClickListener(v -> testConnection());
        btnPick.setOnClickListener(v -> pickZip());
        btnDeploy.setOnClickListener(v -> confirmDeploy());
        btnCheckRelease.setOnClickListener(v -> checkRelease());
        btnInstall.setOnClickListener(v -> downloadAndInstall());

        handleIncomingIntent(getIntent());
        appendLog("모바일 배포 준비됨. 저장소는 현재 앱의 업데이트 저장소를 자동 입력합니다.");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void pickZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        String[] types = {"application/zip", "application/x-zip-compressed", "application/octet-stream"};
        i.putExtra(Intent.EXTRA_MIME_TYPES, types);
        startActivityForResult(i, REQ_PICK_ZIP);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_ZIP && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            loadZip(uri);
        }
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        Uri uri = null;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                //noinspection deprecation
                uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            }
        } catch (Exception ignored) { }
        if (uri != null) loadZip(uri);
    }

    private void loadZip(Uri uri) {
        if (busy) return;
        selectedZipUri = uri;
        selectedZipName = displayName(uri);
        selectedPackage = null;
        latestRelease = null;
        btnDeploy.setEnabled(false);
        btnCheckRelease.setEnabled(false);
        btnInstall.setEnabled(false);
        setStatus(0, "ZIP 검사 중");
        appendLog("ZIP 선택: " + selectedZipName);

        io.execute(() -> {
            try {
                ReleaseZipPackage pkg = ReleaseZipPackage.read(getContentResolver(), uri);
                selectedPackage = pkg;
                String root = pkg.strippedRoot.trim().isEmpty() ? "없음" : pkg.strippedRoot;
                String info = String.format(Locale.KOREA,
                        "%s\n버전 v%s · 파일 %d개 · %s\n최상위 폴더 제거: %s\n검증 통과 ✓",
                        selectedZipName, pkg.version, pkg.files.size(), humanBytes(pkg.totalBytes), root);
                runOnUiThread(() -> {
                    tvZip.setText(info);
                    btnDeploy.setEnabled(true);
                    setStatus(100, "ZIP 검증 완료 · v" + pkg.version);
                    appendLog("VERSION.txt = " + pkg.version + " · Android 전체 소스 구조 확인 완료");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvZip.setText(selectedZipName + "\n검증 실패: " + e.getMessage());
                    setStatus(0, "ZIP 검증 실패");
                    appendLog("ERROR: " + e.getMessage());
                });
            }
        });
    }

    private void testConnection() {
        if (!saveAndValidateSettings()) return;
        setBusy(true);
        setStatus(10, "GitHub 연결 확인 중");
        String repo = repo();
        String branch = branch();
        String token = token();
        appendLog("GitHub 확인: " + repo + " / " + branch);

        io.execute(() -> {
            try {
                ReleaseGitHubApi api = new ReleaseGitHubApi(token);
                ReleaseGitHubApi.RepoInfo info = api.getRepo(repo);
                runOnUiThread(() -> {
                    setStatus(100, "GitHub 연결 성공");
                    appendLog("연결 성공: " + info.fullName + " · " + (info.isPrivate ? "private" : "public") + " · default=" + info.defaultBranch);
                    appendLog("토큰은 Android Keystore 암호화 저장 완료.");
                    setBusy(false);
                });
            } catch (Exception e) {
                runOnUiThread(() -> fail("GitHub 연결 실패", e));
            }
        });
    }

    private void confirmDeploy() {
        if (selectedPackage == null || selectedZipUri == null) {
            toast("먼저 ZIP을 선택하세요.");
            return;
        }
        if (!saveAndValidateSettings()) return;
        String msg = "저장소: " + repo() + "\n"
                + "브랜치: " + branch() + "\n"
                + "배포 버전: v" + selectedPackage.version + "\n"
                + "파일: " + selectedPackage.files.size() + "개\n\n"
                + "GitHub main을 이 소스로 갱신합니다. 성공하면 기존 auto-release-main workflow가 사인 APK를 자동 생성합니다."
                + (cbUpdateWorkflows.isChecked() ? "\n\n⚠ workflow 파일도 이번 ZIP 내용으로 업데이트합니다." : "\n\nworkflow 파일은 기존 것을 보존합니다.");

        new AlertDialog.Builder(this)
                .setTitle("v" + selectedPackage.version + " 배포할까요?")
                .setMessage(msg)
                .setNegativeButton("취소", null)
                .setPositiveButton("배포", (d, w) -> deploy())
                .show();
    }

    private void deploy() {
        setBusy(true);
        latestRelease = null;
        btnInstall.setEnabled(false);
        setStatus(1, "배포 시작");
        appendLog("=== 모바일 배포 v" + selectedPackage.version + " ===");

        final String repo = repo();
        final String branch = branch();
        final String token = token();
        final ReleaseZipPackage pkg = selectedPackage;
        final boolean updateWorkflows = cbUpdateWorkflows.isChecked();

        io.execute(() -> {
            try {
                ReleaseGitHubApi api = new ReleaseGitHubApi(token);
                api.getRepo(repo);
                if (api.releaseExists(repo, pkg.version)) {
                    throw new IllegalStateException("v" + pkg.version + " Release가 이미 존재합니다. VERSION.txt를 올린 새 ZIP을 사용하세요.");
                }
                ReleaseGitHubApi.PushResult result = api.pushZip(repo, branch, pkg, updateWorkflows, this::progressFromWorker);
                runOnUiThread(() -> {
                    btnCheckRelease.setEnabled(true);
                    appendLog("push 완료: " + shortSha(result.commitSha) + " · 업로드 " + result.uploadedFiles + " · 삭제 " + result.deletedFiles);
                    appendLog("GitHub Actions의 signed Release를 자동 대기합니다.");
                });

                ReleaseGitHubApi.ReleaseInfo release = api.waitForRelease(repo, pkg.version, RELEASE_WAIT_MS, this::progressFromWorker);
                latestRelease = release;
                runOnUiThread(() -> {
                    btnInstall.setEnabled(release.hasApk());
                    setStatus(100, "완료 · " + release.tag + " 사인 APK 준비됨");
                    appendLog("RELEASE READY: " + release.tag + " · " + release.apkName);
                    setBusy(false);
                    new AlertDialog.Builder(this)
                            .setTitle("사인 APK 완성 ✓")
                            .setMessage(release.tag + " Release가 생성됐습니다.\n\n이 휴대폰에서 바로 APK를 받아 설치할 수 있습니다.")
                            .setNegativeButton("나중에", null)
                            .setPositiveButton("APK 설치", (d, w) -> downloadAndInstall())
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnCheckRelease.setEnabled(selectedPackage != null);
                    fail("배포/Release 대기 중 중단", e);
                });
            }
        });
    }

    private void checkRelease() {
        if (selectedPackage == null) {
            toast("확인할 버전 ZIP을 먼저 선택하세요.");
            return;
        }
        if (!saveAndValidateSettings()) return;
        setBusy(true);
        setStatus(15, "v" + selectedPackage.version + " Release 확인 중");
        final String repo = repo();
        final String token = token();
        final String version = selectedPackage.version;

        io.execute(() -> {
            try {
                ReleaseGitHubApi.ReleaseInfo release = new ReleaseGitHubApi(token).getRelease(repo, version);
                latestRelease = release;
                runOnUiThread(() -> {
                    btnInstall.setEnabled(release.hasApk());
                    if (release.hasApk()) {
                        setStatus(100, release.tag + " APK 준비됨");
                        appendLog("Release 확인: " + release.apkName);
                    } else {
                        setStatus(50, release.tag + " Release는 있으나 APK 자산 없음");
                        appendLog("Release는 생성됐지만 APK asset을 아직 찾지 못했습니다.");
                    }
                    setBusy(false);
                });
            } catch (ReleaseGitHubApi.ApiException e) {
                runOnUiThread(() -> {
                    if (e.status == 404) {
                        setStatus(25, "아직 v" + version + " Release 없음");
                        appendLog("아직 Release 없음. Actions 빌드 중이거나 실패했을 수 있습니다.");
                        setBusy(false);
                    } else {
                        fail("Release 확인 실패", e);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> fail("Release 확인 실패", e));
            }
        });
    }

    private void downloadAndInstall() {
        if (latestRelease == null || !latestRelease.hasApk()) {
            toast("먼저 사인 Release 상태를 확인하세요.");
            return;
        }
        if (!saveAndValidateSettings()) return;

        if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivity(settings);
            toast("'이 출처 허용'을 켠 뒤 APK 설치 버튼을 다시 눌러주세요.");
            return;
        }

        setBusy(true);
        setStatus(1, "사인 APK 다운로드 시작");
        final String repo = repo();
        final String token = token();
        final ReleaseGitHubApi.ReleaseInfo release = latestRelease;

        io.execute(() -> {
            try {
                File outDir = new File(getCacheDir(), "release_apk");
                File apk = new ReleaseGitHubApi(token).downloadReleaseApk(repo, release, outDir, this::progressFromWorker);
                runOnUiThread(() -> {
                    appendLog("APK 다운로드 완료: " + apk.getName() + " · " + humanBytes(apk.length()));
                    setBusy(false);
                    launchInstaller(apk);
                });
            } catch (Exception e) {
                runOnUiThread(() -> fail("APK 다운로드 실패", e));
            }
        });
    }

    private void launchInstaller(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            setStatus(100, "Android 설치 화면으로 전달 완료");
        } catch (Exception e) {
            fail("설치 화면 열기 실패", e);
        }
    }

    private boolean saveAndValidateSettings() {
        String repo = repo();
        String branch = branch();
        String token = token();
        if (!repo.matches("[^/\\s]+/[^/\\s]+")) {
            toast("저장소를 owner/repository 형식으로 입력하세요.");
            return false;
        }
        if (!branch.matches("[A-Za-z0-9._/-]+")) {
            toast("브랜치 이름을 확인하세요.");
            return false;
        }
        if (token.length() < 20) {
            toast("GitHub token을 입력하세요.");
            return false;
        }
        try {
            secureTokenStore.saveToken(token);
        } catch (Exception e) {
            toast("토큰 보안 저장 실패: " + e.getMessage());
            return false;
        }
        prefs.edit()
                .putString("repo", repo)
                .putString("branch", branch)
                .putBoolean("update_workflows", cbUpdateWorkflows.isChecked())
                .apply();
        return true;
    }

    private String repo() { return etRepo.getText().toString().trim(); }
    private String branch() {
        String b = etBranch.getText().toString().trim();
        return b.trim().isEmpty() ? "main" : b;
    }
    private String token() { return etToken.getText().toString().trim(); }

    private void progressFromWorker(int percent, String message) {
        runOnUiThread(() -> {
            setStatus(percent, message);
            appendLog(message);
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        btnTest.setEnabled(!value);
        btnPick.setEnabled(!value);
        btnDeploy.setEnabled(!value && selectedPackage != null);
        if (value) btnInstall.setEnabled(false);
    }

    private void setStatus(int pct, String text) {
        progress.setProgress(Math.max(0, Math.min(100, pct)));
        tvStatus.setText(text);
    }

    private void fail(String title, Exception e) {
        setBusy(false);
        setStatus(0, title);
        appendLog("ERROR: " + e.getMessage());
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(friendlyError(e))
                .setPositiveButton("확인", null)
                .show();
    }

    private String friendlyError(Exception e) {
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        if (m.contains("401")) return m + "\n\n토큰이 잘못됐거나 만료됐습니다.";
        if (m.contains("403")) return m + "\n\nFine-grained token의 저장소 접근 권한과 Contents: Read and write를 확인하세요. workflow 파일까지 바꾸는 경우 Workflows: Read and write도 필요합니다.";
        if (m.contains("422")) return m + "\n\nmain 브랜치 보호 규칙 또는 동시에 발생한 다른 push를 확인하세요.";
        return m;
    }

    private void appendLog(String line) {
        String old = tvLog.getText().toString();
        String next = old + (old.isEmpty() ? "" : "\n") + line;
        String[] lines = next.split("\\n");
        if (lines.length > 160) {
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - 160; i < lines.length; i++) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(lines[i]);
            }
            next = sb.toString();
        }
        tvLog.setText(next);
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) { }
        if (name == null || name.trim().isEmpty()) name = uri.getLastPathSegment();
        return name == null ? "source.zip" : name;
    }

    private static String shortSha(String sha) {
        if (sha == null) return "";
        return sha.length() <= 8 ? sha : sha.substring(0, 8);
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return new DecimalFormat("0.0").format(kb) + " KB";
        return new DecimalFormat("0.00").format(kb / 1024.0) + " MB";
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
