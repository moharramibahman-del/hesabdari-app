package com.hesabdari.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.FragmentActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.Executor;

public class MainActivity extends FragmentActivity {

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private String cameraPhotoPath;
    private static final int FILE_CHOOSER_REQUEST_CODE = 5173;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 5174;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        }

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setDatabaseEnabled(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                              FileChooserParams fileChooserParams) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;

                Intent galleryIntent = new Intent(Intent.ACTION_GET_CONTENT);
                galleryIntent.addCategory(Intent.CATEGORY_OPENABLE);
                galleryIntent.setType("*/*");

                ArrayList<Intent> initialIntents = new ArrayList<>();
                Intent cameraIntent = buildCameraIntent();
                if (cameraIntent != null) {
                    initialIntents.add(cameraIntent);
                }

                Intent chooser = Intent.createChooser(galleryIntent, "انتخاب فایل");
                if (!initialIntents.isEmpty()) {
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, initialIntents.toArray(new Intent[0]));
                }

                try {
                    startActivityForResult(chooser, FILE_CHOOSER_REQUEST_CODE);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new AndroidBridge(), "Android");

        webView.loadUrl("file:///android_asset/index.html");
    }

    private Intent buildCameraIntent() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        try {
            File photoFile = File.createTempFile("capture_", ".jpg", getCacheDir());
            cameraPhotoPath = photoFile.getAbsolutePath();
            Uri photoUri = FileProvider.getUriForFile(this,
                    getApplicationContext().getPackageName() + ".fileprovider", photoFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            return intent;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) {
                super.onActivityResult(requestCode, resultCode, data);
                return;
            }
            Uri[] results = null;
            if (resultCode == RESULT_OK) {
                if (data == null || data.getData() == null) {
                    if (cameraPhotoPath != null) {
                        File file = new File(cameraPhotoPath);
                        if (file.exists()) {
                            results = new Uri[]{Uri.fromFile(file)};
                        }
                    }
                } else {
                    String dataString = data.getDataString();
                    if (dataString != null) {
                        results = new Uri[]{Uri.parse(dataString)};
                    }
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void forceJsSave() {
        if (webView != null) {
            webView.evaluateJavascript(
                "try{ if(typeof saveData==='function'){ saveData(true); } }catch(e){}", null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        forceJsSave();
    }

    @Override
    protected void onStop() {
        super.onStop();
        forceJsSave();
    }

    private boolean saveBytesToDownloads(String filename, String mimeType, byte[] bytes) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                Uri item = getContentResolver().insert(collection, values);
                if (item == null) return false;
                OutputStream out = getContentResolver().openOutputStream(item);
                if (out == null) return false;
                out.write(bytes);
                out.close();
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(item, values, null, null);
                return true;
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File outFile = new File(dir, filename);
                FileOutputStream fos = new FileOutputStream(outFile);
                fos.write(bytes);
                fos.close();
                return true;
            }
        } catch (Exception e) {
            Log.e("HesabdariBridge", "saveBytesToDownloads failed", e);
            return false;
        }
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    public class AndroidBridge {

        @JavascriptInterface
        public void saveBase64File(String filename, String mimeType, String base64Data) {
            try {
                byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                boolean ok = saveBytesToDownloads(filename, mimeType, bytes);
                toast(ok ? ("فایل در پوشه Download ذخیره شد: " + filename) : "ذخیره‌سازی فایل ناموفق بود.");
            } catch (Exception e) {
                toast("خطا در ذخیره فایل: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void saveTextFile(String filename, String textContent) {
            try {
                byte[] bytes = textContent.getBytes("UTF-8");
                boolean ok = saveBytesToDownloads(filename, "text/plain", bytes);
                toast(ok ? ("فایل ذخیره شد: " + filename) : "ذخیره‌سازی فایل ناموفق بود.");
            } catch (Exception e) {
                toast("خطا در ذخیره فایل: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void printHtmlToPdf(String htmlContent, String filename) {
            runOnUiThread(() -> {
                WebView printWebView = new WebView(MainActivity.this);
                printWebView.getSettings().setJavaScriptEnabled(false);
                printWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                        if (printManager == null) {
                            toast("امکان چاپ در این دستگاه وجود ندارد.");
                            return;
                        }
                        String jobName = filename != null ? filename : "hesabdari-export";
                        PrintDocumentAdapter adapter = view.createPrintDocumentAdapter(jobName);
                        printManager.print(jobName, adapter, new PrintAttributes.Builder().build());
                    }
                });
                printWebView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null);
            });
        }

        @JavascriptInterface
        public void savePdfFromHtml(String htmlContent, String filename) {
            printHtmlToPdf(htmlContent, filename);
        }

        @JavascriptInterface
        public void authenticateBiometric() {
            runOnUiThread(() -> {
                BiometricManager biometricManager = BiometricManager.from(MainActivity.this);
                int canAuth = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
                if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
                    toast("اثر انگشت روی این دستگاه در دسترس نیست.");
                    return;
                }
                Executor executor = ContextCompat.getMainExecutor(MainActivity.this);
                BiometricPrompt prompt = new BiometricPrompt(MainActivity.this, executor,
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                                super.onAuthenticationSucceeded(result);
                                webView.evaluateJavascript(
                                    "document.getElementById('lockModal') && (document.getElementById('lockModal').style.display='none');",
                                    null);
                            }

                            @Override
                            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                                super.onAuthenticationError(errorCode, errString);
                            }
                        });
                BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                        .setTitle("ورود با اثر انگشت")
                        .setSubtitle("برای باز کردن برنامه، اثر انگشت خود را وارد کنید")
                        .setNegativeButtonText("انصراف")
                        .build();
                prompt.authenticate(promptInfo);
            });
        }

        @JavascriptInterface
        public void enableBiometric() {
            runOnUiThread(() -> {
                BiometricManager biometricManager = BiometricManager.from(MainActivity.this);
                int canAuth = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
                if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
                    toast("اثر انگشت با موفقیت فعال شد.");
                } else {
                    toast("اثر انگشت روی این دستگاه در دسترس نیست یا ثبت نشده است.");
                }
            });
        }

        @JavascriptInterface
        public void closeApp() {
            runOnUiThread(MainActivity.this::finishAndRemoveTask);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
