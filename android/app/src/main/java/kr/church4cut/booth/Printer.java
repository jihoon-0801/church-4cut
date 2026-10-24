package kr.church4cut.booth;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 와이파이 프린터(CP1500 등)를 찾고, 확인 화면 없이 바로 인쇄해요. */
final class Printer {
  private Printer() {}

  /** 같은 와이파이의 IPP 프린터 찾기 (몇 초 걸려요) */
  static JSONArray find(Context ctx, int waitMs) {
    final NsdManager nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
    final List<NsdServiceInfo> found = new ArrayList<>();
    final JSONArray out = new JSONArray();
    NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
      @Override public void onStartDiscoveryFailed(String t, int e) {}
      @Override public void onStopDiscoveryFailed(String t, int e) {}
      @Override public void onDiscoveryStarted(String t) {}
      @Override public void onDiscoveryStopped(String t) {}
      @Override public void onServiceFound(NsdServiceInfo s) { synchronized (found) { found.add(s); } }
      @Override public void onServiceLost(NsdServiceInfo s) {}
    };
    try {
      nsd.discoverServices("_ipp._tcp.", NsdManager.PROTOCOL_DNS_SD, dl);
      Thread.sleep(waitMs);
    } catch (Exception ignored) {
    } finally {
      try { nsd.stopServiceDiscovery(dl); } catch (Exception ignored) {}
    }
    ArrayDeque<NsdServiceInfo> q;
    synchronized (found) { q = new ArrayDeque<>(found); }
    // 이름이 같은 항목(IPv4/IPv6 중복)은 한 번만
    List<String> seen = new ArrayList<>();
    for (NsdServiceInfo s : q) {
      if (seen.contains(s.getServiceName())) continue;
      seen.add(s.getServiceName());
      NsdServiceInfo r = resolve(nsd, s);
      if (r == null || r.getHost() == null) continue;
      try {
        String rp = "ipp/print";
        Map<String, byte[]> txt = r.getAttributes();
        if (txt != null && txt.get("rp") != null) rp = new String(txt.get("rp"), StandardCharsets.UTF_8);
        out.put(new JSONObject().put("name", r.getServiceName()).put("url", "ipp://" + hostOf(r.getHost()) + ":" + r.getPort() + "/" + rp));
      } catch (Exception ignored) {}
    }
    return out;
  }

  private static String hostOf(InetAddress a) {
    String h = a.getHostAddress();
    if (h == null) return "";
    int pct = h.indexOf('%'); if (pct >= 0) h = h.substring(0, pct);
    return h.contains(":") ? "[" + h + "]" : h;
  }

  private static NsdServiceInfo resolve(NsdManager nsd, NsdServiceInfo s) {
    final NsdServiceInfo[] res = new NsdServiceInfo[1];
    final CountDownLatch done = new CountDownLatch(1);
    new Handler(Looper.getMainLooper()).post(() -> {
      try {
        nsd.resolveService(s, new NsdManager.ResolveListener() {
          @Override public void onResolveFailed(NsdServiceInfo i, int e) { done.countDown(); }
          @Override public void onServiceResolved(NsdServiceInfo i) { res[0] = i; done.countDown(); }
        });
      } catch (Exception e) { done.countDown(); }
    });
    try { done.await(4, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    return res[0];
  }

  private static String mm(double v) {
    return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.1f", v);
  }

  /** JPEG 한 장을 바로 인쇄해요. 성공하면 프린터가 알려준 정보 문장을 돌려줘요. */
  static String print(String uri, byte[] jpeg, int copies, double wMm, double hMm) throws IOException {
    Ipp.Res a = Ipp.printerAttributes(uri);
    if (!a.ok()) throw new IOException(String.format(Locale.US, "프린터 정보를 받지 못했어요 (0x%04x)", a.status));

    double sw = Math.min(wMm, hMm), sh = Math.max(wMm, hMm);
    String want = mm(sw) + "x" + mm(sh) + "mm";
    String mediaKw = null;
    for (Object m : a.get("media-supported")) if (String.valueOf(m).endsWith("_" + want)) { mediaKw = String.valueOf(m); break; }
    if (mediaKw == null) mediaKw = "custom_" + want.replace("mm", "") + "_" + want;
    boolean canCol = a.has("job-creation-attributes-supported", "media-col") || !a.get("media-col-supported").isEmpty();
    boolean borderless = a.has("media-left-margin-supported", 0) && a.has("media-top-margin-supported", 0);

    // 형식 목록을 안 알려 주는 프린터도 있어서, 그럴 땐 JPEG → PWG 래스터 순서로 직접 보내 봐요
    List<String> fmts = new ArrayList<>();
    List<Object> sup = a.get("document-format-supported");
    if (sup.isEmpty() || sup.contains("image/jpeg")) fmts.add("image/jpeg");
    if (sup.isEmpty() || sup.contains("image/pwg-raster")) fmts.add("image/pwg-raster");
    if (fmts.isEmpty()) throw new IOException("이 프린터가 받는 형식을 아직 못 보내요: " + sup);

    String note = null, why = "";
    for (String fmt : fmts) {
      byte[] doc = "image/jpeg".equals(fmt) ? jpeg : pwg(a, jpeg, sw, sh, mediaKw);
      try {
        for (int i = 0; i < Math.max(1, copies); i++) {
          Ipp.Res r = null;
          if (canCol) r = Ipp.send(uri, job(uri, fmt, a, mediaKw, sw, sh, true, borderless), doc, 120000);
          if (r == null || !r.ok()) r = Ipp.send(uri, job(uri, fmt, a, mediaKw, sw, sh, false, false), doc, 120000);
          if (!r.ok()) throw new IOException(String.format(Locale.US, "프린터가 %s 인쇄를 받지 않았어요 (0x%04x %s)", fmt.substring(6), r.status, r.first("status-message")));
          note = r.first("job-id");
        }
        return note;
      } catch (IOException e) {
        if (note != null) throw e;   // 이미 한 장 나갔으면 다른 형식으로 또 보내지 않아요
        why += (why.isEmpty() ? "" : " / ") + e.getMessage();
      }
    }
    throw new IOException(why + " · 프린터 정보 " + a.attrs.size() + "개");
  }

  private static byte[] pwg(Ipp.Res a, byte[] jpeg, double sw, double sh, String mediaKw) throws IOException {
    int dpi = 300;
    List<Object> rs = a.get("pwg-raster-document-resolution-supported");
    if (!rs.isEmpty() && !rs.contains("300x300")) dpi = Integer.parseInt(String.valueOf(rs.get(0)).split("x")[0]);
    int w = (int) Math.round(sw / 25.4 * dpi), h = (int) Math.round(sh / 25.4 * dpi);
    Bitmap src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
    Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(bm); c.drawColor(Color.WHITE);
    c.drawBitmap(src, null, new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
    src.recycle();
    int[] px = new int[w * h]; bm.getPixels(px, 0, w, 0, 0, w, h); bm.recycle();
    for (int i = 0; i < px.length; i++) px[i] &= 0xffffff;
    List<Object> types = a.get("pwg-raster-document-type-supported");
    return Ipp.pwgRaster(px, w, h, dpi, mediaKw, sw, sh, types.isEmpty() || types.contains("srgb_8"));
  }

  private static byte[] job(String uri, String fmt, Ipp.Res a, String mediaKw, double sw, double sh, boolean col, boolean borderless) throws IOException {
    Ipp.Req q = Ipp.base(Ipp.OP_PRINT_JOB, 2, uri)
        .str(Ipp.V_NAME, "requesting-user-name", "booth")
        .str(Ipp.V_NAME, "job-name", "church-4cut")
        .str(Ipp.V_MIME, "document-format", fmt)
        .group(Ipp.T_JOB);
    if (col) {
      q.begin("media-col")
          .member("media-size").begin("").mnum("x-dimension", (int) Math.round(sw * 100)).mnum("y-dimension", (int) Math.round(sh * 100)).end();
      if (borderless) q.mnum("media-top-margin", 0).mnum("media-bottom-margin", 0).mnum("media-left-margin", 0).mnum("media-right-margin", 0);
      q.end();
    } else {
      q.str(Ipp.V_KEYWORD, "media", mediaKw);
    }
    if (a.has("print-scaling-supported", "fill")) q.str(Ipp.V_KEYWORD, "print-scaling", "fill");
    // 항상 컬러로
    if (a.get("print-color-mode-supported").isEmpty() || a.has("print-color-mode-supported", "color")) q.str(Ipp.V_KEYWORD, "print-color-mode", "color");
    return q.bytes();
  }
}
