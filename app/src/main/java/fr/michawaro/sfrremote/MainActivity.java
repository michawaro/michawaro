package fr.michawaro.sfrremote;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.speech.RecognizerIntent;
import android.widget.Toast;

import org.json.JSONObject;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class MainActivity extends Activity {
    private static final int VOICE_REQUEST = 1409;
    private WebView webView;
    private SfrWebSocket remote;
    private final ExecutorService textQueue = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xff101622);
        getWindow().setNavigationBarColor(0xff101622);
        remote = new SfrWebSocket(new SfrWebSocket.Listener() {
            @Override public void onState(String state, String message) {
                javascript("App.nativeState(" + JSONObject.quote(state) + "," + JSONObject.quote(message) + ")");
            }
            @Override public void onMessage(String message) {
                javascript("App.nativeMessage(" + JSONObject.quote(message) + ")");
            }
        });
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        // The UI is bundled locally, and all socket traffic is handled in native Java.
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !url.startsWith("file:///android_asset/");
            }
        });
        webView.addJavascriptInterface(new Bridge(), "NativeApp");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void javascript(String script) {
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript("(function(){if(window.App){" + script + ";}})()", null);
        });
    }

    private final class Bridge {
        @JavascriptInterface public void connect(String ip) {
            if (!SfrWebSocket.isLocalIPv4(ip)) {
                javascript("App.nativeState('error','Adresse réseau local invalide')");
                return;
            }
            remote.connect(ip);
        }
        @JavascriptInterface public void sendKey(String code) {
            if (SfrWebSocket.validKey(code)) remote.sendKey(code);
        }
        @JavascriptInterface public void status() { remote.sendStatus(); }
        @JavascriptInterface public void speak() { runOnUiThread(MainActivity.this::startSpeech); }
        @JavascriptInterface public void sendText(String value) {
            if (value == null || value.isEmpty()) return;
            String raw = value.substring(0, Math.min(100, value.length()));
            textQueue.execute(() -> {
                String flat = Normalizer.normalize(raw, Normalizer.Form.NFD)
                        .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
                for (int i=0; i<flat.length(); i++) {
                    char c=flat.charAt(i);
                    // EXPERIMENTAL: STB8 is not documented as accepting alphabetic key events.
                    if ((c>='a'&&c<='z') || (c>='0'&&c<='9') || c==' ') {
                        remote.sendExperimentalKey(c == ' ' ? "space" : String.valueOf(c));
                        try { Thread.sleep(100); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
                    }
                }
            });
        }
    }

    private void startSpeech() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Dis : France 2, pause, monte le son…");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try { startActivityForResult(intent, VOICE_REQUEST); }
        catch (ActivityNotFoundException ex) { Toast.makeText(this, "Service de reconnaissance vocale non disponible", Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VOICE_REQUEST && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                javascript("App.voiceResult(" + JSONObject.quote(results.get(0)) + ")");
            }
        }
    }

    @Override protected void onDestroy() {
        if (remote != null) remote.close();
        textQueue.shutdownNow();
        if (webView != null) {
            webView.removeJavascriptInterface("NativeApp");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
