package fr.michawaro.connecttv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import com.altremote.tvremote.ConnectTvController;
import org.json.JSONObject;
import java.util.ArrayList;

public final class MainActivity extends Activity {
    private static final int VOICE_REQUEST = 1409;
    private WebView web;
    private ConnectTvController remote;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xff101622);
        getWindow().setNavigationBarColor(0xff101622);
        remote = new ConnectTvController(this, new ConnectTvController.Listener() {
            @Override public void onState(String state, String message) {
                javascript("App.nativeState(" + JSONObject.quote(state) + "," + JSONObject.quote(message) + ")");
            }
            @Override public void onFound(String name, String host) {
                javascript("App.foundTv(" + JSONObject.quote(name) + "," + JSONObject.quote(host) + ")");
            }
            @Override public void onMessage(String message) {
                javascript("App.nativeMessage(" + JSONObject.quote(message) + ")");
            }
        });
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !url.startsWith("file:///android_asset/");
            }
        });
        web.addJavascriptInterface(new Bridge(), "NativeApp");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    private void javascript(String script) {
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript("(function(){if(window.App){" + script + ";}})()", null);
        });
    }

    private final class Bridge {
        @JavascriptInterface public void connect(String ip) { remote.connect(ip); }
        @JavascriptInterface public void search() { remote.discover(); }
        @JavascriptInterface public void pairCode(String code) { remote.pairCode(code); }
        @JavascriptInterface public void newPair() { remote.newPair(); }
        @JavascriptInterface public void sendKey(String key) { remote.sendKey(key); }
        @JavascriptInterface public void status() { remote.reportState(); }
        @JavascriptInterface public void sendText(String text) {
            if (text != null) remote.sendText(text);
        }
        @JavascriptInterface public void speak() {
            runOnUiThread(MainActivity.this::startSpeech);
        }
    }

    private void startSpeech() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Dis : pause, France 2, augmente le son…");
        try { startActivityForResult(intent, VOICE_REQUEST); }
        catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Service vocal Android indisponible", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == VOICE_REQUEST && result == RESULT_OK && data != null) {
            ArrayList<String> phrases = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (phrases != null && !phrases.isEmpty()) {
                javascript("App.voiceResult(" + JSONObject.quote(phrases.get(0)) + ")");
            }
        }
    }

    @Override protected void onDestroy() {
        if (remote != null) remote.close();
        if (web != null) {
            web.removeJavascriptInterface("NativeApp");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}