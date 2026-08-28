package com.seungjae.jangsu280battery;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v0.27.8 모바일 배포 foreground service.
 * Activity/화면/잠금 상태와 분리해 GitHub push -> signed Release 대기 -> APK 다운로드를 끝까지 이어간다.
 */
public class ReleaseDeployService extends Service {
    public static final String ACTION_START = "com.seungjae.jangsu280battery.release.START";
    public static final String EXTRA_ZIP_PATH = "zip_path";
    public static final String EXTRA_VERSION = "version";
    public static final String EXTRA_ZIP_NAME = "zip_name";

    public static final String PREF_JOB = "release_job_state";
    public static final String KEY_RUNNING = "running";
    public static final String KEY_STATE = "state";
    public static final String KEY_PERCENT = "percent";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_VERSION = "version";
    public static final String KEY_ZIP_PATH = "zip_path";
    public static final String KEY_ZIP_NAME = "zip_name";
    public static final String KEY_APK_PATH = "apk_path";
    public static final String KEY_LOG = "log";
    public static final String KEY_UPDATED_AT = "updated_at";

    public static final String STATE_IDLE = "IDLE";
    public static final String STATE_PREPARING = "PREPARING";
    public static final String STATE_UPLOADING = "UPLOADING";
    public static final String STATE_WAITING = "WAITING";
    public static final String STATE_DOWNLOADING = "DOWNLOADING";
    public static final String STATE_READY = "READY";
    public static final String STATE_FAILED = "FAILED";

    private static final long RELEASE_WAIT_MS = 20L * 60L * 1000L;
    private static final int NOTIFICATION_ID = 2778;
    private static final String CHANNEL_ID = "mobile_release";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);
    private SharedPreferences jobPrefs;
    private SharedPreferences settings;

    @Override
    public void onCreate() {
        super.onCreate();
        jobPrefs = getSharedPreferences(PREF_JOB, MODE_PRIVATE);
        settings = getSharedPreferences("release_settings", MODE_PRIVATE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String zipPath = null;
        String version = null;
        String zipName = null;
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            zipPath = intent.getStringExtra(EXTRA_ZIP_PATH);
            version = intent.getStringExtra(EXTRA_VERSION);
            zipName = intent.getStringExtra(EXTRA_ZIP_NAME);
            jobPrefs.edit()
                    .putString(KEY_ZIP_PATH, zipPath == null ? "" : zipPath)
                    .putString(KEY_VERSION, version == null ? "" : version)
                    .putString(KEY_ZIP_NAME, zipName == null ? "" : zipName)
                    .putString(KEY_APK_PATH, "")
                    .putString(KEY_LOG, "")
                    .putBoolean(KEY_RUNNING, true)
                    .apply();
        } else if (jobPrefs.getBoolean(KEY_RUNNING, false)) {
            zipPath = jobPrefs.getString(KEY_ZIP_PATH, "");
            version = jobPrefs.getString(KEY_VERSION, "");
            zipName = jobPrefs.getString(KEY_ZIP_NAME, "");
        }

        if (zipPath == null || zipPath.trim().isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification initial = buildNotification(1, "모바일 배포 준비 중", false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, initial);
        }
        if (workerRunning.compareAndSet(false, true)) {
            final String finalZipPath = zipPath;
            final String finalVersion = version == null ? "" : version;
            final String finalZipName = zipName == null ? "source.zip" : zipName;
            io.execute(() -> runJob(finalZipPath, finalVersion, finalZipName));
        }
        return START_STICKY;
    }

    private void runJob(String zipPath, String expectedVersion, String zipName) {
        try {
            updateState(STATE_PREPARING, 1, "백그라운드 배포 준비 · " + zipName, true);
            File zipFile = new File(zipPath);
            ReleaseZipPackage pkg = ReleaseZipPackage.read(zipFile);
            if (!expectedVersion.trim().isEmpty() && !expectedVersion.equals(pkg.version)) {
                throw new IllegalStateException("선택 ZIP 버전이 바뀌었습니다. 예상 v" + expectedVersion + " / 실제 v" + pkg.version);
            }

            String repo = settings.getString("repo", BuildConfig.UPDATE_REPOSITORY == null ? "" : BuildConfig.UPDATE_REPOSITORY.trim()).trim();
            String branch = settings.getString("branch", "main").trim();
            boolean updateWorkflows = settings.getBoolean("update_workflows", false);
            String token = new ReleaseSecureTokenStore(this).loadToken().trim();
            if (!repo.matches("[^/\\s]+/[^/\\s]+")) throw new IllegalStateException("GitHub 저장소 설정을 확인하세요.");
            if (token.length() < 20) throw new IllegalStateException("GitHub token이 저장되어 있지 않습니다.");

            ReleaseGitHubApi api = new ReleaseGitHubApi(token);
            api.getRepo(repo);

            ReleaseGitHubApi.ReleaseInfo release = null;
            if (api.releaseExists(repo, pkg.version)) {
                appendLog("v" + pkg.version + " Release가 이미 존재함 · 기존 배포 이어받기");
                try {
                    release = api.getRelease(repo, pkg.version);
                } catch (Exception ignored) { }
            } else {
                updateState(STATE_UPLOADING, 2, "GitHub main 업로드 시작", true);
                ReleaseGitHubApi.PushResult result = api.pushZip(repo, branch, pkg, updateWorkflows,
                        (percent, message) -> updateState(STATE_UPLOADING, percent, message, true));
                appendLog("push 완료 · " + shortSha(result.commitSha) + " · 업로드 " + result.uploadedFiles + " · 삭제 " + result.deletedFiles);
            }

            if (release == null || !release.hasApk()) {
                updateState(STATE_WAITING, 3, "GitHub Actions 사인 APK 대기", true);
                release = api.waitForRelease(repo, pkg.version, RELEASE_WAIT_MS,
                        (percent, message) -> updateState(STATE_WAITING, percent, message, true));
            }

            updateState(STATE_DOWNLOADING, 1, "사인 APK 자동 다운로드 시작", true);
            File outDir = new File(getFilesDir(), "release_apk");
            File apk = api.downloadReleaseApk(repo, release, outDir,
                    (percent, message) -> updateState(STATE_DOWNLOADING, percent, message, true));
            jobPrefs.edit().putString(KEY_APK_PATH, apk.getAbsolutePath()).apply();
            appendLog("APK 준비 완료 · " + apk.getName());
            updateState(STATE_READY, 100, "v" + pkg.version + " 사인 APK 준비됨 · 눌러 설치", false);
            showReadyNotification(pkg.version);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            appendLog("ERROR · " + message);
            updateState(STATE_FAILED, 0, "배포 중단 · " + message, false);
            showFailedNotification(message);
        } finally {
            workerRunning.set(false);
            jobPrefs.edit().putBoolean(KEY_RUNNING, false).apply();
            stopForeground(false);
            stopSelf();
        }
    }

    private void updateState(String state, int percent, String message, boolean running) {
        jobPrefs.edit()
                .putString(KEY_STATE, state)
                .putInt(KEY_PERCENT, Math.max(0, Math.min(100, percent)))
                .putString(KEY_MESSAGE, message == null ? "" : message)
                .putBoolean(KEY_RUNNING, running)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();
        if (running) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(percent, message, false));
        }
    }

    private void appendLog(String line) {
        String old = jobPrefs.getString(KEY_LOG, "");
        String next = old + (old.isEmpty() ? "" : "\n") + line;
        String[] lines = next.split("\\n");
        if (lines.length > 120) {
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - 120; i < lines.length; i++) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(lines[i]);
            }
            next = sb.toString();
        }
        jobPrefs.edit().putString(KEY_LOG, next).apply();
    }

    private Notification buildNotification(int percent, String message, boolean ready) {
        Intent open = new Intent(this, ReleaseUploaderActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_battery_pilot)
                .setContentTitle(ready ? "BatteryPilot 업데이트 준비됨" : "BatteryPilot 모바일 배포 중")
                .setContentText(message == null ? "진행 중" : message)
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(!ready)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (!ready) b.setProgress(100, Math.max(0, Math.min(100, percent)), false);
        return b.build();
    }

    private void showReadyNotification(String version) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.notify(NOTIFICATION_ID, buildNotification(100, "v" + version + " APK 다운로드 완료 · 눌러 설치", true));
    }

    private void showFailedNotification(String error) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        Intent open = new Intent(this, ReleaseUploaderActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_battery_pilot)
                .setContentTitle("BatteryPilot 모바일 배포 중단")
                .setContentText(error)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(error))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        nm.notify(NOTIFICATION_ID, n);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "모바일 소스 배포", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("GitHub 업로드, 사인 Release 대기, APK 다운로드 진행 상태");
        nm.createNotificationChannel(ch);
    }

    private static String shortSha(String sha) {
        if (sha == null) return "";
        return sha.length() <= 8 ? sha : sha.substring(0, 8);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
