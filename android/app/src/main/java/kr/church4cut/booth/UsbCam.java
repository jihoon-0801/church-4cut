package kr.church4cut.booth;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;

import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usb.UVCParam;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 태블릿이 USB 웹캠을 카메라로 인식하지 못해도, 앱이 웹캠(UVC)을 직접 열어요.
 * 여러 크기·방식을 차례로 시험해서 장면이 실제로 들어오는 조합을 찾아요.
 * 받은 장면은 JPEG로 바꿔 두고, 사이트가 한 장씩 가져가요.
 */
final class UsbCam {
  interface Done { void done(boolean ok, String msg, int w, int h); }

  private final Context ctx;
  private final Handler h;
  private final USBMonitor monitor;
  private USBMonitor.UsbControlBlock block;
  private UVCCamera cam;
  private ImageReader reader;
  private Done pending;
  private int wantW = 1280, wantH = 720, camW, camH, camType;
  private boolean running;
  private volatile boolean paused;   // 사이트가 안 볼 때는 변환을 쉬어요

  private final Object lock = new Object();
  private byte[] jpeg;
  private long seq;
  private volatile boolean encoding;
  volatile long rawFrames, glFrames;   // 웹캠에서 받은 장면 수 (원인 찾기용)
  private final StringBuilder log = new StringBuilder();

  UsbCam(Context ctx) {
    this.ctx = ctx.getApplicationContext();
    HandlerThread t = new HandlerThread("usbcam"); t.start();
    h = new Handler(t.getLooper());
    monitor = new USBMonitor(this.ctx, new USBMonitor.OnDeviceConnectListener() {
      @Override public void onAttach(UsbDevice d) {}
      @Override public void onDetach(UsbDevice d) { h.post(() -> { release(); block = null; }); }
      @Override public void onDeviceOpen(UsbDevice d, USBMonitor.UsbControlBlock b, boolean createNew) { h.post(() -> { block = b; if (pending != null) tryAll(); }); }
      @Override public void onDeviceClose(UsbDevice d, USBMonitor.UsbControlBlock b) { h.post(() -> { if (block == b) block = null; }); }
      @Override public void onCancel(UsbDevice d) { h.post(() -> finish(false, "웹캠 사용을 허용하지 않았어요", 0, 0)); }
      @Override public void onError(UsbDevice d, USBMonitor.USBException e) { h.post(() -> finish(false, "웹캠을 열지 못했어요: " + e.getMessage(), 0, 0)); }
    }, h);
    monitor.register();
  }

  static boolean isCamera(UsbDevice d) {
    if (d.getDeviceClass() == UsbConstants.USB_CLASS_VIDEO) return true;
    for (int i = 0; i < d.getInterfaceCount(); i++) if (d.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) return true;
    return false;
  }

  synchronized List<UsbDevice> cameras() {
    List<UsbDevice> out = new ArrayList<>();
    for (UsbDevice d : usbManager().getDeviceList().values()) if (isCamera(d)) out.add(d);
    return out;
  }

  android.hardware.usb.UsbManager usbManager() { return (android.hardware.usb.UsbManager) ctx.getSystemService(Context.USB_SERVICE); }

  /** 꽂힌 USB 장치를 모두 글로 보여줘요 (원인 찾기용) */
  String dump() {
    android.hardware.usb.UsbManager m = usbManager();
    if (m == null) return "이 기기는 USB 장치 연결(USB 호스트)을 지원하지 않아요";
    StringBuilder b = new StringBuilder();
    for (UsbDevice d : m.getDeviceList().values()) {
      b.append(String.format(Locale.US, "%s · %04x:%04x · class %d · 인터페이스", d.getProductName() == null ? "(이름 없음)" : d.getProductName(), d.getVendorId(), d.getProductId(), d.getDeviceClass()));
      for (int i = 0; i < d.getInterfaceCount(); i++) b.append(' ').append(d.getInterface(i).getInterfaceClass());
      b.append(isCamera(d) ? " · 카메라" : "").append(m.hasPermission(d) ? " · 허용됨" : "").append('\n');
    }
    if (log.length() > 0) b.append("\n시험 기록:\n").append(log);
    return b.length() == 0 ? "꽂힌 USB 장치가 하나도 안 보여요" : b.toString().trim();
  }

  /** 웹캠 켜기. 처음에는 "허용할까요?" 창이 떠요. */
  void start(int w, int hgt, Done done) {
    h.post(() -> {
      try {
        wantW = w; wantH = hgt; pending = done; paused = false;
        if (cam != null && running && seq > 0) { finish(true, "", camW, camH); return; }   // 이미 잘 나오고 있으면 그대로
        List<UsbDevice> list = cameras();
        if (list.isEmpty()) { finish(false, "USB 웹캠을 찾지 못했어요. 허브와 선을 확인해 주세요", 0, 0); return; }
        if (block != null) tryAll();
        else monitor.requestPermission(list.get(0));   // 허용되면 onDeviceOpen → tryAll
        h.postDelayed(() -> { if (pending == done) finish(false, "웹캠이 응답하지 않아요. 뽑았다 다시 꽂아 주세요 · " + stats(), 0, 0); }, 60000);
      } catch (Exception e) { finish(false, String.valueOf(e.getMessage()), 0, 0); }
    });
  }

  /** 크기·방식을 바꿔 가며 장면이 들어오는 조합을 찾아요 (각 2.5초) */
  private void tryAll() {
    if (pending == null) return;
    log.setLength(0);
    List<Size> sizes = probeSizes();
    if (sizes == null) { finish(false, "웹캠 정보를 읽지 못했어요 · " + log.toString().trim(), 0, 0); return; }
    List<Object[]> plan = new ArrayList<>();
    int rec = UVCCamera.getRecommendedPlatformQuirks(), alt = rec ^ UVCCamera.UVC_QUIRK_FIX_BANDWIDTH;
    Size mjWant = near(sizes, UVCCamera.UVC_VS_FRAME_MJPEG, wantW, wantH), mjSmall = near(sizes, UVCCamera.UVC_VS_FRAME_MJPEG, 640, 480);
    Size yuSmall = near(sizes, UVCCamera.UVC_VS_FRAME_UNCOMPRESSED, 640, 480), yuTiny = near(sizes, UVCCamera.UVC_VS_FRAME_UNCOMPRESSED, 320, 240);
    for (Size s : new Size[]{mjWant, mjSmall}) if (s != null) { plan.add(new Object[]{s, rec}); plan.add(new Object[]{s, alt}); }
    for (Size s : new Size[]{yuSmall, yuTiny}) if (s != null) { plan.add(new Object[]{s, alt}); plan.add(new Object[]{s, rec}); }
    step(plan, 0);
  }

  private void step(List<Object[]> plan, int i) {
    if (pending == null) return;
    if (i >= plan.size()) { release(); finish(false, "웹캠에서 장면이 오지 않아요 · " + log.toString().trim().replace('\n', ' '), 0, 0); return; }
    Size s = (Size) plan.get(i)[0]; int q = (Integer) plan.get(i)[1];
    String name = (s.type == UVCCamera.UVC_VS_FRAME_MJPEG ? "MJPEG " : "YUYV ") + s.width + "×" + s.height + "@" + s.fps + (q != 0 ? " 대역폭보정" : "");
    long before = rawFrames + glFrames;
    String r = open(s, q);
    if (r != null) { log.append(name).append(": ").append(r).append('\n'); h.post(() -> step(plan, i + 1)); return; }
    h.postDelayed(() -> {
      long got = rawFrames + glFrames - before;
      log.append(name).append(": 장면 ").append(got).append("개\n");
      if (got > 0 && seq > 0) { finish(true, "", camW, camH); return; }
      release();
      step(plan, i + 1);
    }, 2500);
  }

  /** 웹캠이 주는 크기 목록만 먼저 읽어요 */
  private List<Size> probeSizes() {
    UVCCamera c = null;
    try {
      c = new UVCCamera(new UVCParam(null, UVCCamera.getRecommendedPlatformQuirks()));
      int r = c.open(block);
      if (r != 0) { log.append("열기 실패 ").append(r).append('\n'); return null; }
      List<Size> l = c.getSupportedSizeList();
      int mj = 0, yu = 0; for (Size z : l) if (z.type == UVCCamera.UVC_VS_FRAME_MJPEG) mj++; else yu++;
      log.append("크기 MJPEG ").append(mj).append(" · YUYV ").append(yu).append('\n');
      return l;
    } catch (Throwable e) { log.append("열기 오류 ").append(e.getMessage()).append('\n'); return null; }
    finally { if (c != null) try { c.destroy(true); } catch (Throwable ignored) {} }
  }

  private static Size near(List<Size> l, int type, int w, int hgt) {
    Size best = null; long bd = Long.MAX_VALUE;
    for (Size z : l) {
      if (z.type != type) continue;
      long d = Math.abs((long) z.width * z.height - (long) w * hgt) * 2 + (z.width > w ? 1 : 0);
      if (d < bd) { bd = d; best = z; }
    }
    if (best == null) return null;
    best = best.clone();
    if (best.fpsList != null && best.fpsList.contains(30)) best.fps = 30;
    else if (best.fpsList != null && !best.fpsList.isEmpty() && !best.fpsList.contains(best.fps)) best.fps = best.fpsList.get(0);
    return best;
  }

  /** 한 조합으로 열고 시작해요. 실패하면 이유 문장, 성공하면 null */
  private String open(Size s, int quirks) {
    release();
    try {
      cam = new UVCCamera(new UVCParam(s, quirks));
      int r = cam.open(block);
      if (r != 0) return "열기 실패 " + r;
      Size cur = cam.getPreviewSize();
      if (cur == null || cur.width != s.width || cur.height != s.height || cur.type != s.type) return "크기 설정 실패";
      camW = cur.width; camH = cur.height; camType = cur.type;
      final int w = camW, hgt = camH;
      cam.setFrameCallback(f -> encode(f, w, hgt), UVCCamera.PIXEL_FORMAT_NV21);
      // 장면을 받는 두 번째 길: 화면 그리기 판(ImageReader)
      reader = ImageReader.newInstance(w, hgt, PixelFormat.RGBX_8888, 2);
      reader.setOnImageAvailableListener(this::fromReader, h);
      cam.setPreviewDisplay(reader.getSurface());
      int sr = startPreview(cam);
      running = true;
      return sr == 0 ? null : "시작 실패 " + sr;
    } catch (Throwable e) { return "오류 " + e.getMessage(); }
  }

  /** 시작 결과 번호를 알고 싶어서 라이브러리 안쪽 함수를 직접 불러요 */
  private static int startPreview(UVCCamera c) {
    try {
      Field f = UVCCamera.class.getDeclaredField("mNativePtr"); f.setAccessible(true);
      Method m = UVCCamera.class.getDeclaredMethod("nativeStartPreview", long.class); m.setAccessible(true);
      return (Integer) m.invoke(c, f.getLong(c));
    } catch (Throwable e) { c.startPreview(); return 0; }
  }

  private void release() {
    running = false;
    if (cam != null) { try { cam.destroy(true); } catch (Throwable ignored) {} cam = null; }
    if (reader != null) { try { reader.close(); } catch (Throwable ignored) {} reader = null; }
  }

  private synchronized void finish(boolean ok, String msg, int w, int hgt) {
    Done d = pending; pending = null;
    if (d != null) d.done(ok, msg, w, hgt);
  }

  private void encode(ByteBuffer f, int w, int hgt) {
    rawFrames++;
    if (encoding || paused) return;   // 바쁘면 이번 장면은 건너뛰어요
    encoding = true;
    try {
      if (f.remaining() < w * hgt * 3 / 2) return;
      byte[] nv = new byte[w * hgt * 3 / 2]; f.get(nv);
      ByteArrayOutputStream o = new ByteArrayOutputStream(w * hgt / 4);
      new YuvImage(nv, ImageFormat.NV21, w, hgt, null).compressToJpeg(new Rect(0, 0, w, hgt), 85, o);
      publish(o.toByteArray());
    } catch (Exception ignored) {
    } finally { encoding = false; }
  }

  private void fromReader(ImageReader r) {
    Image im = null;
    try {
      im = r.acquireLatestImage(); if (im == null) return;
      glFrames++;
      if (encoding || paused || rawFrames > 0) return;   // 첫 번째 길이 되면 그쪽을 써요
      encoding = true;
      try {
        Image.Plane pl = im.getPlanes()[0]; int w = im.getWidth(), hgt = im.getHeight();
        Bitmap bm = Bitmap.createBitmap(pl.getRowStride() / pl.getPixelStride(), hgt, Bitmap.Config.ARGB_8888);
        bm.copyPixelsFromBuffer(pl.getBuffer());
        if (bm.getWidth() != w) { Bitmap c = Bitmap.createBitmap(bm, 0, 0, w, hgt); bm.recycle(); bm = c; }
        ByteArrayOutputStream o = new ByteArrayOutputStream(w * hgt / 4);
        bm.compress(Bitmap.CompressFormat.JPEG, 85, o); bm.recycle();
        publish(o.toByteArray());
      } finally { encoding = false; }
    } catch (Exception ignored) {
    } finally { if (im != null) im.close(); }
  }

  private void publish(byte[] j) { synchronized (lock) { jpeg = j; seq++; lock.notifyAll(); } }

  /** after보다 새 장면이 생길 때까지 잠깐 기다렸다가 돌려줘요. 없으면 null */
  Object[] frame(long after, long waitMs) {
    synchronized (lock) {
      long end = System.currentTimeMillis() + waitMs;
      while (jpeg == null || seq <= after) {
        long left = end - System.currentTimeMillis();
        if (left <= 0) break;
        try { lock.wait(left); } catch (InterruptedException e) { break; }
      }
      if (jpeg == null || seq <= after) return null;
      return new Object[]{jpeg, seq};
    }
  }

  String stats() {
    synchronized (lock) {
      return "웹캠 장면 " + rawFrames + "개 · 그리기 " + glFrames + "개 · 변환 " + seq + "개 · " + camW + "×" + camH
          + (camType == UVCCamera.UVC_VS_FRAME_MJPEG ? " MJPEG" : " YUYV") + (running ? " · 켜짐" : " · 꺼짐");
    }
  }

  /** 웹캠은 켠 채로 두어요. 다시 켤 때 '쓰는 중' 오류가 나지 않게, 장면만 계속 받아 둬요 */
  void stop() { paused = true; synchronized (lock) { jpeg = null; } }
}
