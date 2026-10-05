package com.tian.gametranslator;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.translate.TranslateLanguage;
import com.google.mlkit.translate.Translation;
import com.google.mlkit.translate.Translator;
import com.google.mlkit.translate.TranslatorOptions;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 7001;
    private static final String PREFS = "translator";
    private static final String PREF_REGION = "region";

    private Translator translator;
    private TextView status;
    private int regionMode = 1; // 0 whole, 1 bottom 45%, 2 center 65%, 3 top 55%
    private Button regionButton;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        regionMode = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_REGION, 1);
        buildUi();
        prepareTranslator(false);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 44, 36, 36);
        root.setBackgroundColor(Color.parseColor("#101216"));

        TextView title = new TextView(this);
        title.setText("Tian Game Translator");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setPadding(0, 0, 0, 16);
        root.addView(title, lp(-1, -2));

        TextView desc = new TextView(this);
        desc.setText("English → Indonesia\nOCR + translation berjalan di perangkat. Hasil mengikuti posisi teks game.");
        desc.setTextColor(Color.LTGRAY);
        desc.setTextSize(15);
        root.addView(desc, lp(-1, -2));

        status = new TextView(this);
        status.setText("Menyiapkan model terjemahan…");
        status.setTextColor(Color.parseColor("#B8BDC7"));
        status.setTextSize(14);
        status.setPadding(0, 20, 0, 18);
        root.addView(status, lp(-1, -2));

        Button model = new Button(this);
        model.setText("Download model English → Indonesia");
        model.setOnClickListener(v -> prepareTranslator(true));
        root.addView(model, lp(-1, -2));

        regionButton = new Button(this);
        regionButton.setText(regionLabel());
        regionButton.setOnClickListener(v -> cycleRegion());
        root.addView(regionButton, lp(-1, -2));

        Button overlay = new Button(this);
        overlay.setText("1. Izinkan overlay");
        overlay.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } else {
                Toast.makeText(this, "Overlay sudah diizinkan", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(overlay, lp(-1, -2));

        Button start = new Button(this);
        start.setText("2. Mulai translator");
        start.setOnClickListener(v -> startCapture());
        root.addView(start, lp(-1, -2));

        TextView note = new TextView(this);
        note.setText("Untuk dialog game, mulai dengan area BAWAH 45%. Jika dialog ada di tempat lain, tekan tombol area. Teks lama dipertahankan beberapa detik saat OCR gagal sesaat.");
        note.setTextColor(Color.parseColor("#B8BDC7"));
        note.setTextSize(13);
        note.setPadding(0, 20, 0, 0);
        root.addView(note, lp(-1, -2));

        setContentView(root);
    }

    private String regionLabel() {
        switch (regionMode) {
            case 0: return "Area OCR: SELURUH LAYAR";
            case 2: return "Area OCR: TENGAH 65%";
            case 3: return "Area OCR: ATAS 55%";
            default: return "Area OCR: BAWAH 45%";
        }
    }

    private void cycleRegion() {
        regionMode = (regionMode + 1) % 4;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_REGION, regionMode).apply();
        regionButton.setText(regionLabel());
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private void prepareTranslator(boolean showToast) {
        if (translator == null) {
            TranslatorOptions options = new TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(TranslateLanguage.INDONESIAN)
                    .build();
            translator = Translation.getClient(options);
        }
        status.setText("Mengunduh/mengecek model English → Indonesia…");
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(v -> {
                    status.setText("Model siap. Bisa mulai translator.");
                    if (showToast) Toast.makeText(this, "Model siap", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    status.setText("Gagal download model: " + e.getMessage());
                    if (showToast) Toast.makeText(this, "Download model gagal", Toast.LENGTH_LONG).show();
                });
    }

    private void startCapture() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Aktifkan izin overlay dulu", Toast.LENGTH_LONG).show();
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE || data == null || resultCode != RESULT_OK) return;
        Intent i = new Intent(this, TranslateService.class)
                .putExtra("resultCode", resultCode)
                .putExtra("data", data)
                .putExtra("regionMode", regionMode);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        Toast.makeText(this, "Translator aktif. Buka game.", Toast.LENGTH_SHORT).show();
    }

    @Override protected void onDestroy() {
        if (translator != null) translator.close();
        super.onDestroy();
    }
}
