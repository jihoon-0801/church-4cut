package kr.church4cut.booth;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 아주 작은 IPP 클라이언트: 프린터 정보 묻기(Get-Printer-Attributes)와 인쇄(Print-Job)만 해요. */
public final class Ipp {
  static final int OP_PRINT_JOB = 0x0002, OP_GET_PRINTER_ATTRIBUTES = 0x000B;
  static final int T_OPERATION = 0x01, T_JOB = 0x02, T_END = 0x03;
  static final int V_INTEGER = 0x21, V_BOOLEAN = 0x22, V_ENUM = 0x23, V_RESOLUTION = 0x32, V_RANGE = 0x33,
      V_BEG_COLLECTION = 0x34, V_END_COLLECTION = 0x37, V_NAME = 0x42, V_KEYWORD = 0x44, V_URI = 0x45,
      V_CHARSET = 0x47, V_LANGUAGE = 0x48, V_MIME = 0x49, V_MEMBER = 0x4A;

  /** 요청 본문을 쌓는 도구 */
  public static final class Req {
    final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    final DataOutputStream out = new DataOutputStream(buf);

    public Req(int op, int id) throws IOException { out.writeShort(0x0200); out.writeShort(op); out.writeInt(id); }
    public Req group(int tag) throws IOException { out.writeByte(tag); return this; }
    void raw(int tag, String name, byte[] v) throws IOException {
      out.writeByte(tag); byte[] n = name.getBytes(StandardCharsets.UTF_8);
      out.writeShort(n.length); out.write(n); out.writeShort(v.length); out.write(v);
    }
    public Req str(int tag, String name, String v) throws IOException { raw(tag, name, v.getBytes(StandardCharsets.UTF_8)); return this; }
    public Req strs(int tag, String name, String... vs) throws IOException { for (int i = 0; i < vs.length; i++) str(tag, i == 0 ? name : "", vs[i]); return this; }
    public Req num(int tag, String name, int v) throws IOException { raw(tag, name, ByteBuffer.allocate(4).putInt(v).array()); return this; }
    public Req begin(String name) throws IOException { raw(V_BEG_COLLECTION, name, new byte[0]); return this; }
    public Req end() throws IOException { raw(V_END_COLLECTION, "", new byte[0]); return this; }
    /** 모음(collection) 안의 항목 이름 */
    public Req member(String name) throws IOException { raw(V_MEMBER, "", name.getBytes(StandardCharsets.UTF_8)); return this; }
    public Req mnum(String name, int v) throws IOException { member(name); return num(V_INTEGER, "", v); }
    public byte[] bytes() throws IOException { out.writeByte(T_END); out.flush(); return buf.toByteArray(); }
  }

  /** 응답: 상태 코드와 속성들(이름 → 값 목록). 모음(collection) 속성은 건너뛰어요. */
  public static final class Res {
    public int status;
    public final Map<String, List<Object>> attrs = new LinkedHashMap<>();
    public boolean ok() { return status < 0x0100; }
    public List<Object> get(String k) { List<Object> v = attrs.get(k); return v == null ? new ArrayList<>() : v; }
    public boolean has(String k, Object v) { for (Object o : get(k)) if (o.equals(v)) return true; return false; }
    public String first(String k) { List<Object> v = get(k); return v.isEmpty() ? "" : String.valueOf(v.get(0)); }
  }

  public static Res parse(byte[] b) {
    ByteBuffer in = ByteBuffer.wrap(b);
    Res r = new Res();
    in.getShort(); r.status = in.getShort() & 0xffff; in.getInt();
    String last = null; int depth = 0;
    while (in.hasRemaining()) {
      int tag = in.get() & 0xff;
      if (tag == T_END) break;
      if (tag < 0x10) { last = null; continue; }   // 그룹 구분
      int nl = in.getShort() & 0xffff; byte[] nb = new byte[nl]; in.get(nb);
      int vl = in.getShort() & 0xffff; byte[] vb = new byte[vl]; in.get(vb);
      if (tag == V_BEG_COLLECTION) { if (depth == 0 && nl > 0) last = new String(nb, StandardCharsets.UTF_8); depth++; continue; }
      if (tag == V_END_COLLECTION) { depth--; continue; }
      if (depth > 0) continue;
      String name = nl > 0 ? new String(nb, StandardCharsets.UTF_8) : last;
      if (name == null) continue;
      last = name;
      Object v;
      ByteBuffer vv = ByteBuffer.wrap(vb);
      if ((tag == V_INTEGER || tag == V_ENUM) && vl == 4) v = vv.getInt();
      else if (tag == V_BOOLEAN && vl == 1) v = vb[0] != 0;
      else if (tag == V_RESOLUTION && vl == 9) v = vv.getInt() + "x" + vv.getInt();
      else if (tag == V_RANGE && vl == 8) v = vv.getInt() + "-" + vv.getInt();
      else if (tag >= 0x40) v = new String(vb, StandardCharsets.UTF_8);
      else v = "";
      List<Object> list = r.attrs.get(name);
      if (list == null) { list = new ArrayList<>(); r.attrs.put(name, list); }
      list.add(v);
    }
    return r;
  }

  /** ipp:// 주소를 http:// 로 바꿔서 보내요 */
  public static String httpUrl(String uri) {
    if (uri.startsWith("ipp://")) { String rest = uri.substring(6); int slash = rest.indexOf('/'); String host = slash < 0 ? rest : rest.substring(0, slash);
      return "http://" + (host.contains(":") && !host.endsWith("]") ? host : host + ":631") + (slash < 0 ? "/" : rest.substring(slash)); }
    return uri;
  }

  public static Res send(String uri, byte[] head, byte[] doc, int timeoutMs) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(httpUrl(uri)).openConnection();
    try {
      c.setConnectTimeout(5000); c.setReadTimeout(timeoutMs);
      c.setDoOutput(true); c.setRequestMethod("POST");
      c.setRequestProperty("Content-Type", "application/ipp");
      c.setFixedLengthStreamingMode(head.length + (doc == null ? 0 : doc.length));
      try (OutputStream o = c.getOutputStream()) { o.write(head); if (doc != null) o.write(doc); }
      int code = c.getResponseCode();
      if (code != 200) throw new IOException("프린터가 HTTP " + code + " 로 답했어요");
      try (InputStream i = c.getInputStream()) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] t = new byte[8192]; int n;
        while ((n = i.read(t)) > 0) b.write(t, 0, n);
        return parse(b.toByteArray());
      }
    } finally { c.disconnect(); }
  }

  static Req base(int op, int id, String uri) throws IOException {
    return new Req(op, id).group(T_OPERATION)
        .str(V_CHARSET, "attributes-charset", "utf-8")
        .str(V_LANGUAGE, "attributes-natural-language", "ko-kr")
        .str(V_URI, "printer-uri", uri.startsWith("http://") ? "ipp://" + uri.substring(7) : uri);
  }

  public static Res printerAttributes(String uri) throws IOException {
    Req q = base(OP_GET_PRINTER_ATTRIBUTES, 1, uri).str(V_NAME, "requesting-user-name", "booth")
        .strs(V_KEYWORD, "requested-attributes", "printer-description", "media-col-database");
    return send(uri, q.bytes(), null, 15000);
  }

  /** PWG 래스터(image/pwg-raster) 한 장 만들기. 픽셀은 0xRRGGBB, 줄마다 압축해요. */
  public static byte[] pwgRaster(int[] px, int w, int h, int dpi, String sizeName, double wMm, double hMm, boolean srgb) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream(w * h);
    b.write("RaS2".getBytes(StandardCharsets.US_ASCII));
    ByteBuffer hd = ByteBuffer.allocate(1796);
    putStr(hd, 0, "PwgRaster");
    hd.putInt(276, dpi); hd.putInt(280, dpi);                 // HWResolution
    hd.putInt(340, 1);                                        // NumCopies
    hd.putInt(352, (int) Math.round(wMm / 25.4 * 72)); hd.putInt(356, (int) Math.round(hMm / 25.4 * 72));   // PageSize (pt)
    hd.putInt(372, w); hd.putInt(376, h);                     // Width, Height
    hd.putInt(384, 8); hd.putInt(388, 24); hd.putInt(392, w * 3);   // BitsPerColor, BitsPerPixel, BytesPerLine
    hd.putInt(396, 0); hd.putInt(400, srgb ? 19 : 1);         // ColorOrder chunky, ColorSpace sRGB/RGB
    hd.putInt(420, 3);                                        // NumColors
    hd.putInt(452, 1); hd.putInt(456, 1); hd.putInt(460, 1);  // TotalPageCount, CrossFeedTransform, FeedTransform
    putStr(hd, 1732, sizeName);
    b.write(hd.array());
    int y = 0;
    while (y < h) {
      int rep = 1;
      while (y + rep < h && rep < 256 && sameRow(px, w, y, y + rep)) rep++;
      b.write(rep - 1);
      int o = y * w, x = 0;
      while (x < w) {
        int run = 1;
        while (x + run < w && run < 128 && px[o + x + run] == px[o + x]) run++;
        if (run > 1) { b.write(run - 1); rgb(b, px[o + x]); x += run; continue; }
        int lit = 1;   // 다음 칸과 다른 동안 그대로 적기
        while (x + lit < w && lit < 128 && (x + lit + 1 >= w || px[o + x + lit] != px[o + x + lit + 1])) lit++;
        b.write(lit == 1 ? 0 : 257 - lit); for (int i = 0; i < lit; i++) rgb(b, px[o + x + i]); x += lit;
      }
      y += rep;
    }
    return b.toByteArray();
  }
  static boolean sameRow(int[] px, int w, int a, int c) { for (int x = 0, oa = a * w, oc = c * w; x < w; x++) if (px[oa + x] != px[oc + x]) return false; return true; }
  static void rgb(ByteArrayOutputStream b, int p) { b.write((p >> 16) & 0xff); b.write((p >> 8) & 0xff); b.write(p & 0xff); }
  static void putStr(ByteBuffer hd, int at, String s) { byte[] v = s.getBytes(StandardCharsets.US_ASCII); for (int i = 0; i < Math.min(63, v.length); i++) hd.put(at + i, v[i]); }
}
