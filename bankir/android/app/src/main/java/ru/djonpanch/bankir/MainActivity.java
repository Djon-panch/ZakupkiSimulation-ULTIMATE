package ru.djonpanch.bankir;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * WebView-обёртка игры «Госзакупки: Симулятор ULTIMATE».
 * - JS + DOM storage (localStorage) = сейвы игры работают как в браузере
 * - NativeSave: экспорт сейва файлом в «Загрузки»
 * - WebChromeClient: импорт сейва через системный выбор файла
 * - Кнопка «Назад» сначала закрывает модальное окно игры
 */
public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_REQ = 101;
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // localStorage сейвов
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100); // отключаем автоскейлинг текста WebView — иначе значки/шрифты раздуваются на телефонах

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new Chrome());
        web.addJavascriptInterface(new SaveBridge(), "NativeSave");

        // На Android 7–9 нужен явный грант для записи в «Загрузки»
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 201);
        }

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl("file:///android_asset/index.html");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        // Сначала закрываем модалку игры, потом history, потом выходим
        final String js =
                "(function(){var m=document.querySelector('.modal.active');" +
                "if(m){m.classList.remove('active');" +
                "try{if(typeof stopAnalysisClock==='function')stopAnalysisClock();" +
                "if(typeof stopAuctionClock2==='function')stopAuctionClock2();}catch(e){}" +
                "return '1';}return '0';})()";
        web.evaluateJavascript(js, v -> {
            if (!"\"1\"".equals(v)) {
                if (web.canGoBack()) web.goBack();
                else finish();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQ) {
            if (fileCallback != null) {
                Uri[] res = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    res = new Uri[]{data.getData()};
                }
                fileCallback.onReceiveValue(res);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }

    private class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView webView,
                                         ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(null);
            }
            fileCallback = callback;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"application/json", "application/octet-stream", "*/*"});
            try {
                startActivityForResult(
                        Intent.createChooser(i, "Выберите файл сейва"), FILE_CHOOSER_REQ);
            } catch (Exception e) {
                fileCallback = null;
                return false;
            }
            return true;
        }
    }

    /** Мост вызова из JS: NativeSave.save('имя.json', 'содержимое') */
    public class SaveBridge {
        @JavascriptInterface
        public boolean save(String name, String content) {
            try {
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    cv.put(MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS);
                    Uri u = getContentResolver().insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (u == null) {
                        return false;
                    }
                    try (OutputStream os = getContentResolver().openOutputStream(u)) {
                        if (os == null) {
                            return false;
                        }
                        os.write(bytes);
                    }
                    final String path = Environment.DIRECTORY_DOWNLOADS + "/" + name;
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "💾 Сейв сохранён: " + path, Toast.LENGTH_LONG).show());
                    return true;
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists() && !dir.mkdirs()) {
                        return false;
                    }
                    File f = new File(dir, name);
                    try (OutputStream os = new FileOutputStream(f)) {
                        os.write(bytes);
                    }
                    final String path = f.getAbsolutePath();
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "💾 Сейв сохранён: " + path, Toast.LENGTH_LONG).show());
                    return true;
                }
            } catch (Exception e) {
                final String msg = (e.getMessage() == null) ? "неизвестная ошибка" : e.getMessage();
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Ошибка экспорта: " + msg, Toast.LENGTH_LONG).show());
                return false;
            }
        }
    }
}
