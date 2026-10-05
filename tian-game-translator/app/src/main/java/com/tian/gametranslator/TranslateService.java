package com.tian.gametranslator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TranslateService extends Service {
    private static final String CHANNEL = "tian_translate";
    private static final int NOTIFICATION_ID = 9011;
    private static final int MAX_BLOCKS = 8;
    private static final long KEEP_MS = 15000L;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread workerThread;
    private Handler worker;
    private Handler main;
    private WindowManager wm;

    private FrameLayout overlayRoot;
    private TextView scanButton;
    private final Map<String, OverlayEntry> entries = new HashMap<>();

    private TextRecognizer recognizer;
    private Translator translator;

    private int screenW, screenH;
    private int regionMode = 1;
    private volatile boolean scanRequested;
    private volatile boolean processing;

    @Override public void onCreate() {
        super.onCreate();
        main = new Handler(getMainLooper());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        createNotificationChannel();
        startForegroundCompat();

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        translator = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.INDONESIAN)
                .build());

        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build());

        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

        workerThread = new HandlerThread("tian-screenshot-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    private void startForegroundCompat() {
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Tian Game Translator")
                .setContentText("Tekan tombol T untuk menerjemahkan layar")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Tian Translator", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        regionMode = intent.getIntExtra("regionMode", 1);
        int result = intent.getIntExtra("resultCode", 0);
        Intent data = intent.getParcelableExtra("data");

        if (data != null && projection == null) {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(result, data);
            setupCapture();
            createOverlayRoot();
            showFloatingButton();
        }

        return START_NOT_STICKY;
    }

    private void setupCapture() {
        reader = ImageReader.newInstance(
                screenW, screenH, PixelFormat.RGBA_8888, 2);

        display = projection.createVirtualDisplay(
                "TianTranslator",
                screenW, screenH,
                getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, worker);

        reader.setOnImageAvailableListener(r -> {
            if (!scanRequested || processing) return;
            Image image = r.acquireLatestImage();
            if (image == null) return;

            scanRequested = false;
            processing = true;
            processImage(image);
        }, worker);

        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                stopCapture();
                stopSelf();
            }
        }, worker);
    }

    private void showFloatingButton() {
        if (scanButton != null) return;

        scanButton = new TextView(this);
        scanButton.setText("T");
        scanButton.setTextColor(Color.WHITE);
        scanButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        scanButton.setGravity(Gravity.CENTER);
        scanButton.setBackgroundColor(0xE61B75D1);
        scanButton.setElevation(dp(8));

        scanButton.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;
            private boolean moved;

            @Override public boolean onTouch(View v, MotionEvent e) {
                WindowManager.LayoutParams p =
                        (WindowManager.LayoutParams) v.getLayoutParams();

                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = p.x;
                    startY = p.y;
                    moved = false;
                    return true;
                }

                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    int nx = startX + (int) (e.getRawX() - downX);
                    int ny = startY + (int) (e.getRawY() - downY);
                    if (Math.abs(nx - startX) > dp(6) || Math.abs(ny - startY) > dp(6)) {
                        moved = true;
                    }
                    p.x = nx;
                    p.y = ny;
                    try { wm.updateViewLayout(v, p); } catch (Throwable ignored) {}
                    return true;
                }

                if (e.getAction() == MotionEvent.ACTION_UP) {
                    if (!moved) requestScan();
                    return true;
                }
                return true;
            }
        });

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                dp(52), dp(52),
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        p.gravity = Gravity.TOP | Gravity.RIGHT;
        p.x = dp(18);
        p.y = dp(80);

        wm.addView(scanButton, p);
    }

    private void requestScan() {
        if (processing) return;
        scanRequested = true;

        if (scanButton != null) {
            main.post(() -> {
                scanButton.setText("…");
                scanButton.setAlpha(0.65f);
            });
        }
    }

    private void processImage(Image image) {
        Bitmap full = null;
        Bitmap crop = null;

        try {
            full = imageToBitmap(image);
            image.close();

            Rect region = getRegion(full.getWidth(), full.getHeight());
            crop = Bitmap.createBitmap(
                    full, region.left, region.top,
                    region.width(), region.height());

            InputImage input = InputImage.fromBitmap(crop, 0);
            Rect offset = new Rect(region);

            Bitmap finalFull = full;
            Bitmap finalCrop = crop;

            recognizer.process(input)
                    .addOnSuccessListener(result ->
                            handleOcr(result, finalCrop.getWidth(), offset))
                    .addOnFailureListener(e -> finishScan())
                    .addOnCompleteListener(t -> {
                        try { finalCrop.recycle(); } catch (Throwable ignored) {}
                        try { finalFull.recycle(); } catch (Throwable ignored) {}
                    });

        } catch (Throwable t) {
            try { image.close(); } catch (Throwable ignored) {}
            if (crop != null) try { crop.recycle(); } catch (Throwable ignored) {}
            if (full != null) try { full.recycle(); } catch (Throwable ignored) {}
            finishScan();
        }
    }

    private void handleOcr(Text result, int captureW, Rect region) {
        List<Text.TextBlock> blocks = new ArrayList<>(result.getTextBlocks());
        blocks.removeIf(b ->
                b.getText() == null ||
                b.getText().trim().length() < 2 ||
                b.getBoundingBox() == null);

        blocks.sort(Comparator.comparingInt(b -> b.getBoundingBox().top));

        final List<PendingTranslation> pending = new ArrayList<>();
        int accepted = 0;

        for (Text.TextBlock block : blocks) {
            if (accepted >= MAX_BLOCKS) break;

            String raw = normalize(block.getText());
            if (!looksLikeEnglish(raw)) continue;

            Rect local = new Rect(block.getBoundingBox());
            Rect box = new Rect(
                    local.left + region.left,
                    local.top + region.top,
                    local.right + region.left,
                    local.bottom + region.top);

            pending.add(new PendingTranslation(raw, box));
            accepted++;
        }

        if (pending.isEmpty()) {
            finishScan();
            return;
        }

        final int[] remaining = {pending.size()};
        final boolean[] hadSuccess = {false};

        for (PendingTranslation p : pending) {
            translator.translate(p.raw)
                    .addOnSuccessListener(translated -> {
                        p.translated = translated;
                        hadSuccess[0] = true;
                        remaining[0]--;
                        if (remaining[0] == 0) {
                            applyBatch(pending, captureW, hadSuccess[0]);
                        }
                    })
                    .addOnFailureListener(e -> {
                        remaining[0]--;
                        if (remaining[0] == 0) {
                            applyBatch(pending, captureW, hadSuccess[0]);
                        }
                    });
        }
    }

    private void applyBatch(List<PendingTranslation> pending,
                             int captureW, boolean hadSuccess) {
        main.post(() -> {
            if (hadSuccess) {
                clearOverlays();
                for (PendingTranslation p : pending) {
                    if (p.translated == null || p.translated.trim().isEmpty()) continue;
                    addOverlay(p.raw, p.translated, p.box, captureW);
                }
            }
            finishScan();
        });
    }

    private void addOverlay(String raw, String translated, Rect box, int captureW) {
        int x = clamp(box.left - dp(6), dp(4), screenW - dp(80));
        int y = clamp(box.top - dp(5), dp(4), screenH - dp(60));

        int sourceW = Math.max(dp(80), box.width());
        int sourceH = Math.max(dp(26), box.height());

        int maxW = Math.min(dp(520), screenW - x - dp(8));
        int width = clamp(sourceW + dp(18), dp(130), Math.max(dp(130), maxW));

        int fontSp = (int) clampFloat(
                (sourceH / getResources().getDisplayMetrics().scaledDensity) * 0.62f,
                11f, 18f);

        TextView tv = new TextView(this);
        tv.setText(translated);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp);
        tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        tv.setIncludeFontPadding(true);
        tv.setMaxLines(4);
        tv.setPadding(dp(9), dp(5), dp(9), dp(5));
        tv.setShadowLayer(2.5f, 0, 1.0f, Color.BLACK);

        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xD91B1B1B);
        bg.setCornerRadius(dp(7));
        bg.setStroke(dp(1), 0x66FFFFFF);
        tv.setBackground(bg);

        int estimatedLines = Math.max(1,
                (int) Math.ceil(translated.length() / Math.max(10f, width / Math.max(8f, fontSp * 0.55f))));
        int height = clamp(
                Math.max(sourceH + dp(12), dp(34) * estimatedLines),
                dp(38), screenH / 3);

        if (x + width > screenW - dp(4)) {
            x = Math.max(dp(4), screenW - width - dp(4));
        }
        if (y + height > screenH - dp(4)) {
            y = Math.max(dp(4), screenH - height - dp(4));
        }

        FrameLayout.LayoutParams lp =
                new FrameLayout.LayoutParams(width, height);
        lp.leftMargin = x;
        lp.topMargin = y;

        overlayRoot.addView(tv, lp);

        String key = makeSlotKey(box);
        entries.put(key, new OverlayEntry(key, raw, tv, System.currentTimeMillis()));
    }

    private Rect getRegion(int w, int h) {
        if (regionMode == 0) return new Rect(0, 0, w, h);

        if (regionMode == 2) {
            int top = (int) (h * 0.175f);
            return new Rect(0, top, w, (int) (h * 0.825f));
        }

        if (regionMode == 3) {
            return new Rect(0, 0, w, (int) (h * 0.55f));
        }

        return new Rect(0, (int) (h * 0.55f), w, h);
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        buffer.rewind();

        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();

        Bitmap temp = Bitmap.createBitmap(
                image.getWidth() + rowPadding / pixelStride,
                image.getHeight(),
                Bitmap.Config.ARGB_8888);

        temp.copyPixelsFromBuffer(buffer);

        Bitmap result = Bitmap.createBitmap(
                temp, 0, 0, image.getWidth(), image.getHeight());

        temp.recycle();
        return result;
    }

    private boolean looksLikeEnglish(String text) {
        int letters = 0;
        int latin = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                    latin++;
                }
            }
        }

        return letters >= 2 && latin >= Math.max(2, letters / 2);
    }

    private String normalize(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private String makeSlotKey(Rect b) {
        int qx = Math.max(0, b.centerX() / dp(120));
        int qy = Math.max(0, b.centerY() / dp(70));
        return qx + ":" + qy;
    }

    private void clearOverlays() {
        if (overlayRoot == null) return;
        overlayRoot.removeAllViews();
        entries.clear();
    }

    private void finishScan() {
        processing = false;
        main.post(() -> {
            if (scanButton != null) {
                scanButton.setText("T");
                scanButton.setAlpha(1f);
            }
        });
    }

    private int dp(int value) {
        return Math.max(1,
                (int) (value * getResources().getDisplayMetrics().density + 0.5f));
    }

    private float clampFloat(float v, float lo, float hi) {
        return Math.max(lo, Math.min(v, hi));
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(v, hi));
    }

    private void createOverlayRoot() {
        if (overlayRoot != null) return;

        overlayRoot = new FrameLayout(this);

        WindowManager.LayoutParams rootParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        rootParams.gravity = Gravity.TOP | Gravity.LEFT;
        wm.addView(overlayRoot, rootParams);
    }

    private void startOverlayRootIfNeeded() {
        main.post(this::createOverlayRoot);
    }

    private void removeFloatingButton() {
        if (scanButton != null) {
            try { wm.removeView(scanButton); } catch (Throwable ignored) {}
            scanButton = null;
        }
    }

    private void stopCapture() {
        removeFloatingButton();

        if (reader != null) {
            try { reader.close(); } catch (Throwable ignored) {}
            reader = null;
        }

        if (display != null) {
            try { display.release(); } catch (Throwable ignored) {}
            display = null;
        }

        if (projection != null) {
            try { projection.stop(); } catch (Throwable ignored) {}
            projection = null;
        }

        main.post(() -> {
            if (overlayRoot != null) {
                try { wm.removeView(overlayRoot); } catch (Throwable ignored) {}
                overlayRoot = null;
            }
        });
    }

    @Override public void onDestroy() {
        stopCapture();

        if (recognizer != null) {
            try { recognizer.close(); } catch (Throwable ignored) {}
        }

        if (translator != null) {
            try { translator.close(); } catch (Throwable ignored) {}
        }

        if (workerThread != null) workerThread.quitSafely();

        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private static class PendingTranslation {
        final String raw;
        final Rect box;
        String translated;

        PendingTranslation(String raw, Rect box) {
            this.raw = raw;
            this.box = box;
        }
    }

    private static class OverlayEntry {
        final String slot;
        final String raw;
        final TextView view;
        final long lastSeen;

        OverlayEntry(String slot, String raw, TextView view, long lastSeen) {
            this.slot = slot;
            this.raw = raw;
            this.view = view;
            this.lastSeen = lastSeen;
        }
    }
}
