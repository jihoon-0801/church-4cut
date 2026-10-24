package kr.church4cut.booth;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** 부스 사이트를 화면 가득 열고, 사이트가 프린터로 바로 인쇄할 수 있게 다리를 놓아요. */
public class MainActivity extends Activity {
  static final String SITE = "https://jihoon-0801.github.io/church-4cut/";
  static final int REQ_CAMERA = 1, REQ_FILE = 2;

  WebView web;
  UsbCam usb;
  PermissionRequest pendingPerm;
  ValueCallback<Uri[]> pendingFile;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    web = new WebView(this);
    setContentView(web);
    hideBars();

    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setDatabaseEnabled(true);
    s.setMediaPlaybackRequiresUserGesture(false);
    s.setAllowFileAccess(false);
    s.setUserAgentString(s.getUserAgentString() + " Church4cutApp/1");

    usb = new UsbCam(this);
    web.setWebViewClient(new WebViewClient() {
      @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return usbFrame(r.getUrl()); }
    });
    if (Build.VERSION.SDK_INT >= 24) {
      ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
        @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest r) { return usbFrame(r.getUrl()); }
      });
    }
    web.setWebChromeClient(new WebChromeClient() {
      @Override public void onPermissionRequest(PermissionRequest r) {
        runOnUiThread(() -> {
          if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) r.grant(r.getResources());
          else { pendingPerm = r; requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA); }
        });
      }
      @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
        if (pendingFile != null) pendingFile.onReceiveValue(null);
        pendingFile = cb;
        try {
          Intent i = p.createIntent();
          if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
          startActivityForResult(i, REQ_FILE);
        } catch (Exception e) { pendingFile = null; return false; }
        return true;
      }
    });
    web.addJavascriptInterface(new Bridge(), "BoothApp");
    if (b != null) web.restoreState(b); else web.loadUrl(SITE);
  }

  /** 사이트가 __uvc/frame?s=마지막번호 로 USB 웹캠 장면을 가져가요 */
  WebResourceResponse usbFrame(Uri u) {
    if (u == null || u.getPath() == null || !u.getPath().endsWith("/__uvc/frame")) return null;
    long after = -1;
    try { after = Long.parseLong(u.getQueryParameter("s")); } catch (Exception ignored) {}
    Object[] f = usb.frame(after, 1000);
    Map<String, String> hd = new HashMap<>();
    hd.put("Cache-Control", "no-store");
    if (f == null) return new WebResourceResponse("image/jpeg", null, 204, "No Frame", hd, new ByteArrayInputStream(new byte[0]));
    hd.put("X-Seq", String.valueOf(f[1]));
    return new WebResourceResponse("image/jpeg", null, 200, "OK", hd, new ByteArrayInputStream((byte[]) f[0]));
  }

  @Override protected void onDestroy() { usb.stop(); super.onDestroy(); }

  @Override protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); web.saveState(o); }
  @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideBars(); }
  /** 부스로 쓰는 동안 뒤로 가기로 앱이 꺼지지 않게 해요 (끝낼 때는 홈 버튼) */
  @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); }

  @SuppressWarnings("deprecation")
  void hideBars() {
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
  }

  @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
    if (code == REQ_CAMERA && pendingPerm != null) {
      if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) pendingPerm.grant(pendingPerm.getResources());
      else pendingPerm.deny();
      pendingPerm = null;
    }
  }

  @Override protected void onActivityResult(int code, int result, Intent data) {
    super.onActivityResult(code, result, data);
    if (code == REQ_FILE && pendingFile != null) {
      Uri[] out = null;
      if (result == RESULT_OK && data != null) {
        if (data.getClipData() != null) {
          out = new Uri[data.getClipData().getItemCount()];
          for (int i = 0; i < out.length; i++) out[i] = data.getClipData().getItemAt(i).getUri();
        } else if (data.getData() != null) out = new Uri[]{data.getData()};
      }
      pendingFile.onReceiveValue(out);
      pendingFile = null;
    }
  }

  /** 사이트에 결과 돌려주기: window.__boothCb(id, {ok, ...}) */
  void reply(String id, JSONObject o) {
    String js = "window.__boothCb&&window.__boothCb(" + JSONObject.quote(id) + "," + o + ")";
    runOnUiThread(() -> web.evaluateJavascript(js, null));
  }

  static JSONObject fail(Exception e) {
    try { return new JSONObject().put("ok", false).put("msg", e.getMessage() == null ? e.toString() : e.getMessage()); }
    catch (Exception x) { return new JSONObject(); }
  }

  final class Bridge {
    @JavascriptInterface public String version() { return "4"; }

    /** USB 웹캠이 꽂혀 있는지 (이름 목록) */
    @JavascriptInterface public String uvcList() {
      org.json.JSONArray a = new org.json.JSONArray();
      try { for (android.hardware.usb.UsbDevice d : usb.cameras()) a.put(d.getProductName() == null ? "USB 웹캠" : d.getProductName()); } catch (Exception ignored) {}
      return a.toString();
    }

    @JavascriptInterface public void uvcStart(String id, int w, int h) {
      usb.start(w, h, (ok, msg, rw, rh) -> {
        try { reply(id, new JSONObject().put("ok", ok).put("msg", msg).put("w", rw).put("h", rh)); } catch (Exception ignored) {}
      });
    }

    @JavascriptInterface public void uvcStop() { usb.stop(); }

    @JavascriptInterface public String usbDump() { try { return usb.dump(); } catch (Exception e) { return "확인 실패: " + e; } }

    /** 확인 화면 없이 바로 인쇄. url이 비어 있으면 프린터를 찾아서 첫 번째로 보내요. */
    @JavascriptInterface public void print(String id, String b64, int copies, double wMm, double hMm, String url) {
      new Thread(() -> {
        try {
          String u = url;
          if (u == null || u.isEmpty()) {
            org.json.JSONArray list = Printer.find(MainActivity.this, 3000);
            if (list.length() == 0) throw new Exception("와이파이에서 프린터를 찾지 못했어요");
            u = list.getJSONObject(0).getString("url");
            for (int i = 0; i < list.length(); i++) {
              String n = list.getJSONObject(i).getString("name").toUpperCase();
              if (n.contains("SELPHY") || n.contains("CP1500")) { u = list.getJSONObject(i).getString("url"); break; }
            }
          }
          byte[] jpeg = Base64.decode(b64, Base64.DEFAULT);
          String job = Printer.print(u, jpeg, copies, wMm, hMm);
          reply(id, new JSONObject().put("ok", true).put("url", u).put("job", job));
        } catch (Exception e) { reply(id, fail(e)); }
      }).start();
    }

    @JavascriptInterface public void find(String id) {
      new Thread(() -> {
        try { reply(id, new JSONObject().put("ok", true).put("list", Printer.find(MainActivity.this, 4000))); }
        catch (Exception e) { reply(id, fail(e)); }
      }).start();
    }

    /** 바로 인쇄가 안 될 때: 안드로이드 인쇄 화면으로 지금 페이지를 인쇄 */
    @JavascriptInterface public void printDialog(double wMm, double hMm) {
      runOnUiThread(() -> {
        PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
        PrintAttributes.MediaSize ms = new PrintAttributes.MediaSize("booth", "Booth",
            (int) Math.round(Math.min(wMm, hMm) / 25.4 * 1000), (int) Math.round(Math.max(wMm, hMm) / 25.4 * 1000));
        PrintAttributes at = new PrintAttributes.Builder().setMediaSize(Math.round(wMm) == 100 && Math.round(hMm) == 148 ? PrintAttributes.MediaSize.JPN_HAGAKI : ms)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS).setColorMode(PrintAttributes.COLOR_MODE_COLOR).build();
        pm.print("church-4cut", web.createPrintDocumentAdapter("church-4cut"), at);
      });
    }

    /** 사진·파일을 기기에 저장 (사진은 갤러리, 나머지는 다운로드 폴더) */
    @JavascriptInterface public void save(String id, String b64, String name, String mime) {
      new Thread(() -> {
        try {
          byte[] data = Base64.decode(b64, Base64.DEFAULT);
          boolean img = mime != null && mime.startsWith("image/");
          if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, img ? Environment.DIRECTORY_PICTURES + "/교회네컷" : Environment.DIRECTORY_DOWNLOADS);
            Uri u = getContentResolver().insert(img ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new Exception("저장할 곳을 만들지 못했어요");
            try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(data); }
          } else {
            File dir = getExternalFilesDir(img ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS);
            try (FileOutputStream o = new FileOutputStream(new File(dir, name))) { o.write(data); }
          }
          reply(id, new JSONObject().put("ok", true));
        } catch (Exception e) { reply(id, fail(e)); }
      }).start();
    }
  }
}
