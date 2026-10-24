package kr.church4cut.booth;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.HandlerThread;

import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usb.UVCParam;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 태블릿이 USB 웹캠을 카메라로 인식하지 못해도, 앱이 웹캠(UVC)을 직접 열어요.
 * 받은 장면은 JPEG로 바꿔 두고, 사이트가 __uvc/frame 주소로 한 장씩 가져가요.
 */
final class UsbCam {
  interface Done { void done(boolean ok, String msg, int w, int h); }

  private final Context ctx;
  private final Handler h;
  private ICameraHelper helper;
  private UsbDevice dev;
  private Done pending;
  private int wantW = 1280, wantH = 720;

  private final Object lock = new Object();
  private byte[] jpeg;
  private long seq;
  private volatile boolean encoding;

  UsbCam(Context ctx) {
    this.ctx = ctx.getApplicationContext();
    HandlerThread t = new HandlerThread("usbcam"); t.start();
    h = new Handler(t.getLooper());
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
      b.append(String.format(java.util.Locale.US, "%s · %04x:%04x · class %d · 인터페이스", d.getProductName() == null ? "(이름 없음)" : d.getProductName(), d.getVendorId(), d.getProductId(), d.getDeviceClass()));
      for (int i = 0; i < d.getInterfaceCount(); i++) b.append(' ').append(d.getInterface(i).getInterfaceClass());
      b.append(isCamera(d) ? " · 카메라" : "").append(m.hasPermission(d) ? " · 허용됨" : "").append('\n');
    }
    return b.length() == 0 ? "꽂힌 USB 장치가 하나도 안 보여요" : b.toString().trim();
  }

  /**
   * 라이브러리가 권하는 순서대로 열어요: 장치 고르기(허용 창) → 카메라 열기 → 화면 받기.
   * 라이브러리가 안 보이는 화면에 먼저 그려 줘야 장면이 흘러나와요.
   */
  private void ensureHelper() {
    if (helper != null) return;
    helper = new CameraHelper();
    helper.setStateCallback(new ICameraHelper.StateCallback() {
      @Override public void onAttach(UsbDevice d) {}
      @Override public void onDeviceOpen(UsbDevice d, boolean first) {
        Size want = new Size(UVCCamera.UVC_VS_FRAME_MJPEG, wantW, wantH, 30, new ArrayList<>());
        helper.openCamera(new UVCParam(want, UVCCamera.getRecommendedPlatformQuirks()));
      }
      @Override public void onCameraOpen(UsbDevice d) {
        try {
          Size cur = helper.getPreviewSize();
          final int w = cur.width, hgt = cur.height;
          helper.setFrameCallback(f -> encode(f, w, hgt), UVCCamera.PIXEL_FORMAT_NV21);
          helper.startPreview();
          finish(true, "", w, hgt);
        } catch (Exception e) { finish(false, "웹캠 화면을 켜지 못했어요: " + e.getMessage(), 0, 0); }
      }
      @Override public void onCameraClose(UsbDevice d) {}
      @Override public void onDeviceClose(UsbDevice d) {}
      @Override public void onDetach(UsbDevice d) { synchronized (lock) { jpeg = null; } }
      @Override public void onCancel(UsbDevice d) { finish(false, "웹캠 사용을 허용하지 않았어요", 0, 0); }
      @Override public void onError(UsbDevice d, com.herohan.uvcapp.CameraException e) { finish(false, "웹캠 오류: " + e.getMessage(), 0, 0); }
    });
  }

  /** 웹캠 켜기. 처음에는 "허용할까요?" 창이 떠요. */
  void start(int w, int hgt, Done done) {
    h.post(() -> {
      try {
        close();
        wantW = w; wantH = hgt; pending = done;
        List<UsbDevice> list = cameras();
        if (list.isEmpty()) { finish(false, "USB 웹캠을 찾지 못했어요. 허브와 선을 확인해 주세요", 0, 0); return; }
        ensureHelper();
        dev = list.get(0);
        helper.selectDevice(dev);
        // 열리는 데 오래 걸리면 알려줘요
        h.postDelayed(() -> { if (pending == done) finish(false, "웹캠이 응답하지 않아요. 뽑았다 다시 꽂아 주세요", 0, 0); }, 15000);
      } catch (Exception e) { finish(false, String.valueOf(e.getMessage()), 0, 0); }
    });
  }

  private synchronized void finish(boolean ok, String msg, int w, int hgt) {
    Done d = pending; pending = null;
    if (d != null) d.done(ok, msg, w, hgt);
  }

  private void encode(ByteBuffer f, int w, int hgt) {
    if (encoding) return;   // 바쁘면 이번 장면은 건너뛰어요
    encoding = true;
    try {
      byte[] nv = new byte[f.remaining()]; f.get(nv);
      ByteArrayOutputStream o = new ByteArrayOutputStream(w * hgt / 4);
      new YuvImage(nv, ImageFormat.NV21, w, hgt, null).compressToJpeg(new Rect(0, 0, w, hgt), 85, o);
      synchronized (lock) { jpeg = o.toByteArray(); seq++; lock.notifyAll(); }
    } catch (Exception ignored) {
    } finally { encoding = false; }
  }

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

  void close() {
    // 다음에 켤 때 처음부터 다시 열리도록 연결을 통째로 정리해요
    ICameraHelper hp = helper; helper = null;
    if (hp != null) { try { hp.stopPreview(); } catch (Exception ignored) {} try { hp.closeCamera(); } catch (Exception ignored) {} try { hp.release(); } catch (Exception ignored) {} }
    synchronized (lock) { jpeg = null; }
  }

  void stop() { h.post(this::close); }
}
