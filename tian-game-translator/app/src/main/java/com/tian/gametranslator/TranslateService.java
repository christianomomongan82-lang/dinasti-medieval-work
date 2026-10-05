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
import android.view.Gravity;
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
    private static final long SCAN_MS = 1400;
    private static final long KEEP_MS = 9000;
    private static final int MAX_BLOCKS = 6;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread workerThread;
    private Handler worker;
    private Handler main;
    private WindowManager wm;
    private FrameLayout overlayRoot;
    private final Map<String, OverlayEntry> entries = new HashMap<>();
    private TextRecognizer recognizer;
    private Translator translator;
    private int screenW, screenH;
    private boolean processing;
    private long lastFrameTime;
    private int regionMode = 1;

    @Override public void onCreate() {
        super.onCreate();
        main = new Handler(getMainLooper());
        createNotificationChannel();
        startForegroundCompat();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        translator = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.INDONESIAN)
                .build());
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build());

        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

        workerThread = new HandlerThread("tian-ocr-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        overlayRoot = new FrameLayout(this);
        WindowManager.LayoutParams rootParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        rootParams.gravity = Gravity.TOP | Gravity.LEFT;
        wm.addView(overlayRoot, rootParams);
    }

    private void startForegroundCompat() {
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Tian Game Translator")
                .setContentText("English → Indonesia aktif")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Tian Translator", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        regionMode = intent.getIntExtra("regionMode", 1);
        int result = intent.getIntExtra("resultCode", 0);
        Intent data = intent.getParcelableExtra("data");
        if (data != null) {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(result, data);
            startCapture();
        }
        return START_NOT_STICKY;
    }

    private void startCapture() {
        int density = getResources().getDisplayMetrics().densityDpi;
        reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay("TianTranslator",
                screenW, screenH, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, worker);
        reader.setOnImageAvailableListener(r -> {
            long now = System.currentTimeMillis();
            if (now - lastFrameTime < SCAN_MS || processing) return;
            lastFrameTime = now;
            processLatestImage();
        }, worker);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopCapture(); stopSelf(); }
        }, worker);
    }

    private void processLatestImage() {
        Image image = null;
        Bitmap full = null;
        Bitmap crop = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            processing = true;
            full = imageToBitmap(image);
            image.close();
            image = null;

            Rect region = getRegion(full.getWidth(), full.getHeight());
            crop = Bitmap.createBitmap(full, region.left, region.top, region.width(), region.height());
            InputImage input = InputImage.fromBitmap(crop, 0);
            Rect offset = new Rect(region);
            Bitmap finalCrop = crop;
            Bitmap finalFull = full;
            recognizer.process(input)
                    .addOnSuccessListener(result -> handleOcr(result, finalCrop.getWidth(), finalCrop.getHeight(), offset))
                    .addOnFailureListener(e -> { /* keep last good overlays */ })
                    .addOnCompleteListener(t -> {
                        try { finalCrop.recycle(); } catch (Throwable ignored) {}
                        try { finalFull.recycle(); } catch (Throwable ignored) {}
                        processing = false;
                    });
        } catch (Throwable t) {
            if (image != null) try { image.close(); } catch (Throwable ignored) {}
            if (crop != null) try { crop.recycle(); } catch (Throwable ignored) {}
            if (full != null) try { full.recycle(); } catch (Throwable ignored) {}
            processing = false;
        }
    }

    private Rect getRegion(int w, int h) {
        if (regionMode == 0) return new Rect(0, 0, w, h);
        if (regionMode == 2) {
            int top = (int) (h * 0.175f);
            return new Rect(0, top, w, (int) (h * 0.825f));
        }
        if (regionMode == 3) return new Rect(0, 0, w, (int) (h * 0.55f));
        return new Rect(0, (int) (h * 0.55f), w, h);
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        buffer.rewind();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        Bitmap temp = Bitmap.createBitmap(image.getWidth() + rowPadding / pixelStride, image.getHeight(), Bitmap.Config.ARGB_8888);
        temp.copyPixelsFromBuffer(buffer);
        Bitmap result = Bitmap.createBitmap(temp, 0, 0, image.getWidth(), image.getHeight());
        temp.recycle();
        return result;
    }

    private void handleOcr(Text result, int captureW, int captureH, Rect region) {
        List<Text.TextBlock> blocks = new ArrayList<>(result.getTextBlocks());
        blocks.removeIf(b -> b.getText() == null || b.getText().trim().length() < 2 || b.getBoundingBox() == null);
        blocks.sort(Comparator.comparingInt(b -> b.getBoundingBox().top));
        int count = 0;
        for (Text.TextBlock block : blocks) {
            if (count++ >= MAX_BLOCKS) break;
            String raw = normalize(block.getText());
            if (!looksLikeEnglish(raw)) continue;
            Rect local = new Rect(block.getBoundingBox());
            Rect box = new Rect(local.left + region.left, local.top + region.top,
                    local.right + region.left, local.bottom + region.top);
            handleBlock(raw, box, captureW, captureH);
        }
        pruneExpired();
    }

    private void handleBlock(String raw, Rect box, int captureW, int captureH) {
        final String slot = makeSlotKey(box);
        OverlayEntry old = entries.get(slot);
        long now = System.currentTimeMillis();
        if (old != null && raw.equals(old.raw)) {
            old.lastSeen = now;
            return;
        }
        final long token = now;
        if (old != null) old.pendingToken = token;
        translator.translate(raw)
                .addOnSuccessListener(translated -> showOverlay(slot, raw, translated, box, captureW, captureH, token))
                .addOnFailureListener(e -> { /* retain old good translation */ });
    }

    private boolean looksLikeEnglish(String text) {
        int letters = 0;
        int latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) latin++;
            }
        }
        return letters >= 2 && latin >= Math.max(2, letters / 2);
    }

    private String normalize(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private void showOverlay(String slot, String raw, String translated, Rect box, int captureW, int captureH, long token) {
        main.post(() -> {
            OverlayEntry current = entries.get(slot);
            if (current != null && current.raw.equals(raw) && current.lastRequest > token) return;

            float sx = screenW / (float) captureW;
            float sy = screenH / (float) captureH;
            int x = clamp((int) (box.left * sx) - 6, 4, Math.max(4, screenW - 80));
            int y = clamp((int) (box.top * sy) - 5, 4, Math.max(4, screenH - 60));
            int sourceW = Math.max(1, (int) (box.width() * sx));
            int sourceH = Math.max(1, (int) (box.height() * sy));
            int minW = dp(150);
            int maxW = (int) (screenW * 0.92f);
            int w = clamp(Math.max(sourceW + dp(18), minW), minW, maxW);
            if (x + w > screenW - 4) x = Math.max(4, screenW - w - 4);
            int h = clamp(Math.max(sourceH + dp(14), dp(42)), dp(42), (int) (screenH * 0.25f));
            if (y + h > screenH - 4) y = Math.max(4, screenH - h - 4);

            TextView tv = new TextView(this);
            tv.setText(translated);
            tv.setTextColor(Color.WHITE);
            tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            tv.setIncludeFontPadding(false);
            tv.setMaxLines(3);
            tv.setPadding(dp(8), dp(5), dp(8), dp(5));
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    clampFloat(sourceH / getResources().getDisplayMetrics().scaledDensity * 0.55f, 10f, 17f));
            tv.setShadowLayer(2.5f, 0, 1, Color.BLACK);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xE61A1A1A);
            bg.setCornerRadius(dp(7));
            bg.setStroke(dp(1), 0x70FFFFFF);
            tv.setBackground(bg);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
            lp.leftMargin = x;
            lp.topMargin = y;
            overlayRoot.addView(tv, lp);

            if (current != null && current.view != null) {
                try { overlayRoot.removeView(current.view); } catch (Throwable ignored) {}
            }
            OverlayEntry fresh = new OverlayEntry(slot, raw, tv, System.currentTimeMillis());
            fresh.lastRequest = token;
            entries.put(slot, fresh);
        });
    }

    private String makeSlotKey(Rect b) {
        int qx = Math.max(0, b.centerX() / dp(110));
        int qy = Math.max(0, b.centerY() / dp(65));
        return qx + ":" + qy;
    }

    private void pruneExpired() {
        long now = System.currentTimeMillis();
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, OverlayEntry> e : entries.entrySet()) {
            if (now - e.getValue().lastSeen > KEEP_MS) dead.add(e.getKey());
        }
        for (String k : dead) {
            OverlayEntry oe = entries.remove(k);
            if (oe != null) main.post(() -> {
                try { overlayRoot.removeView(oe.view); } catch (Throwable ignored) {}
            });
        }
    }

    private int dp(int value) {
        return Math.max(1, (int) (value * getResources().getDisplayMetrics().density + 0.5f));
    }

    private float clampFloat(float v, float lo, float hi) { return Math.max(lo, Math.min(v, hi)); }
    private int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(v, hi)); }

    private void stopCapture() {
        if (reader != null) { reader.close(); reader = null; }
        if (display != null) { display.release(); display = null; }
        if (projection != null) { projection.stop(); projection = null; }
    }

    @Override public void onDestroy() {
        stopCapture();
        if (recognizer != null) recognizer.close();
        if (translator != null) translator.close();
        if (workerThread != null) workerThread.quitSafely();
        if (overlayRoot != null && wm != null) {
            try { wm.removeView(overlayRoot); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private static class OverlayEntry {
        final String slot;
        final String raw;
        final TextView view;
        volatile long lastSeen;
        volatile long lastRequest;
        volatile long pendingToken;
        OverlayEntry(String slot, String raw, TextView view, long lastSeen) {
            this.slot = slot;
            this.raw = raw;
            this.view = view;
            this.lastSeen = lastSeen;
        }
    }
}
