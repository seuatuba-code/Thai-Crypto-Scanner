package th.cryptoscanner;

import android.app.*;
import android.os.*;
import android.widget.*;
import android.view.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ToneGenerator;
import android.media.AudioManager;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {

    final int BG = 0xff0B1120;
    final int CARD = 0xff192333;
    final int GREEN = 0xff34D399;
    final int RED = 0xffF87171;
    final int WHITE = 0xffF8FAFC;
    final int GRAY = 0xff94A3B8;

    EditText capital, fee1, fee2, buffer, apiKey, apiSecret;
    TextView status, best, stats;
    LinearLayout results;
    Button scan;
    CheckBox auto, sound;

    Handler h = new Handler(Looper.getMainLooper());
    ExecutorService worker = Executors.newSingleThreadExecutor();
    ExecutorService pair = Executors.newFixedThreadPool(2);

    boolean active = false;
    boolean busy = false;
    long rounds = 0;
    long hits = 0;
    long lastAlert = 0;
    double record = Double.NEGATIVE_INFINITY;

    String history = "";
    List<String> coins = new ArrayList<>();
    long refreshed = 0;

    int dp(float n) {
        return (int)(n * getResources().getDisplayMetrics().density);
    }

    GradientDrawable shape(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(12));
        return d;
    }

    TextView text(LinearLayout l, String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(0, dp(3), 0, dp(3));
        l.addView(t);
        return t;
    }

    LinearLayout card(LinearLayout parent) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(1);
        l.setPadding(dp(14), dp(12), dp(14), dp(12));
        l.setBackground(shape(CARD));
        LinearLayout.LayoutParams p =
            new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(5), 0, dp(5));
        parent.addView(l, p);
        return l;
    }

    EditText field(LinearLayout l, String name, String value) {
        text(l, name, 13, GRAY);
        EditText e = new EditText(this);
        e.setTextColor(WHITE);
        e.setTextSize(19);
        e.setInputType(8194);
        e.setText(value);
        l.addView(e);
        return e;
    }

    Button button(LinearLayout l, String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(BG);
        b.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(GREEN));
        l.addView(b);
        return b;
    }

    public void onCreate(Bundle b) {
        super.onCreate(b);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(1);
        root.setPadding(dp(16), dp(32), dp(16), dp(24));

        scroll.addView(root);
        setContentView(scroll);

        TextView title = text(root, "THAI CRYPTO SCANNER", 23, WHITE);
        title.setTypeface(null, Typeface.BOLD);

        text(root, "V4.0 PRO • BITKUB × BINANCE TH", 13, GREEN);
        text(root, "ข้อมูลตลาดจาก API | ซื้อขายจำลอง", 13, GRAY);

        LinearLayout summary = card(root);

        text(summary, "โอกาสกำไรสูงสุดในรอบล่าสุด", 14, GRAY);
        best = text(summary, "-- บาท", 32, GREEN);
        stats = text(summary, "รอการสแกน", 14, WHITE);
        status = text(summary, "กำลังเตรียมระบบ", 12, GRAY);

        LinearLayout settings = card(root);

        capital = field(settings, "มูลค่าซื้อจำลอง (บาท)", "10000");
        fee1 = field(settings, "ค่าธรรมเนียม Bitkub (%)", "0.25");
        fee2 = field(settings, "ค่าธรรมเนียม Binance TH (%)", "0.25");
        buffer = field(settings, "ต้นทุนสำรองเพิ่มเติม (บาท)", "10");

        text(settings, "Bitkub API แบบอ่านข้อมูลเท่านั้น (ไม่บันทึกรหัส)", 13, GRAY);
        apiKey = new EditText(this); apiKey.setTextColor(WHITE); apiKey.setHintTextColor(GRAY); apiKey.setHint("API Key"); apiKey.setSingleLine(true); apiKey.setInputType(129); settings.addView(apiKey);
        apiSecret = new EditText(this); apiSecret.setTextColor(WHITE); apiSecret.setHintTextColor(GRAY); apiSecret.setHint("API Secret"); apiSecret.setSingleLine(true); apiSecret.setInputType(129); settings.addView(apiSecret);
        apiKey.setSaveEnabled(false); apiSecret.setSaveEnabled(false);
        auto = new CheckBox(this);
        auto.setText("สแกนอัตโนมัติ");
        auto.setTextColor(WHITE);
        auto.setChecked(true);
        settings.addView(auto);

        sound = new CheckBox(this);
        sound.setText("เสียงแจ้งเตือนเมื่อพบกำไร");
        sound.setTextColor(WHITE);
        sound.setChecked(true);
        settings.addView(sound);

        scan = button(root, "สแกนตลาดทันที");
        scan.setOnClickListener(v -> runScan());

        Button share = button(root, "แชร์ประวัติ CSV");
        share.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT,
                "time,coin,buy,sell,net,roi,transfer_verified,network\n" + history);
            startActivity(Intent.createChooser(i, "แชร์ประวัติ"));
        });

        text(root, "อันดับโอกาสทำกำไร", 20, WHITE);
        text(root, "เรียงจากกำไรประมาณการสูงสุด", 12, GRAY);

        results = new LinearLayout(this);
        results.setOrientation(1);
        root.addView(results);

        history = getPreferences(0).getString("history_v4", "");
        rounds = getPreferences(0).getLong("rounds_v4", 0);
        hits = getPreferences(0).getLong("hits_v4", 0);

        try {
            record = Double.parseDouble(
                getPreferences(0).getString("record_v4", "-Infinity"));
        } catch(Exception ignored) {}
    }

    Runnable tick = new Runnable() {
        public void run() {
            if (active) {
                if (auto.isChecked()) runScan();
                h.postDelayed(this, 10000);
            }
        }
    };

    protected void onResume() {
        super.onResume();
        active = true;
        h.post(tick);
    }

    protected void onPause() {
        active = false;
        h.removeCallbacks(tick);
        super.onPause();
    }

    protected void onDestroy() {
        worker.shutdownNow();
        pair.shutdownNow();
        super.onDestroy();
    }

    JSONObject get(String url) throws Exception { return get(url, Collections.emptyMap()); }
    JSONObject get(String url, Map<String,String> headers) throws Exception {
        HttpURLConnection c =
            (HttpURLConnection)new URL(url).openConnection();

        c.setInstanceFollowRedirects(false);
        for (Map.Entry<String,String> entry : headers.entrySet()) c.setRequestProperty(entry.getKey(), entry.getValue());
        c.setConnectTimeout(7000);
        c.setReadTimeout(7000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "ThaiCryptoScanner/4.0");

        try {
            int code = c.getResponseCode();

            if (code != 200)
                throw new IOException("HTTP " + code);

            try (InputStream in = c.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {

                byte[] buf = new byte[4096];
                int n;

                while ((n = in.read(buf)) != -1)
                    out.write(buf, 0, n);

                return new JSONObject(out.toString("UTF-8"));
            }
        } finally {
            c.disconnect();
        }
    }

    void discover() throws Exception {

        if (!coins.isEmpty() &&
            SystemClock.elapsedRealtime() - refreshed < 600000)
            return;

        JSONObject bk = get(
            "https://api.bitkub.com/api/v3/market/symbols");

        JSONArray list = bk.getJSONArray("result");

        Set<String> available = new HashSet<>();

        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);

            if ("THB".equalsIgnoreCase(o.optString("quote_asset"))
                && "active".equalsIgnoreCase(o.optString("status"))
                && !o.optBoolean("freeze_buy")
                && !o.optBoolean("freeze_sell")
                && !"broker".equalsIgnoreCase(o.optString("source"))) {

                String coin = o.optString("base_asset");
                if (coin.matches("[A-Z0-9]+")) available.add(coin);
            }
        }

        JSONObject bn = get(
            "https://api.binance.th/api/v1/exchangeInfo");

        if (bn.optJSONObject("data") != null)
            bn = bn.getJSONObject("data");

        JSONArray symbols = bn.getJSONArray("symbols");
        Set<String> shared = new TreeSet<>();

        for (int i = 0; i < symbols.length(); i++) {
            JSONObject o = symbols.getJSONObject(i);
            String coin = o.optString("baseAsset");

            if ("THB".equals(o.optString("quoteAsset"))
                && "TRADING".equals(o.optString("status"))
                && available.contains(coin)) {
                shared.add(coin);
            }
        }

        if (shared.isEmpty())
            throw new IOException("ไม่พบคู่เหรียญร่วมกัน");

        coins = new ArrayList<>(shared);
        refreshed = SystemClock.elapsedRealtime();
    }

    static class Book {
        List<double[]> asks;
        List<double[]> bids;
        long at;

        Book(JSONObject j) throws Exception {
            asks = levels(j.getJSONArray("asks"), false);
            bids = levels(j.getJSONArray("bids"), true);
            at = SystemClock.elapsedRealtime();

            if (asks.isEmpty() || bids.isEmpty())
                throw new IOException("Order book ว่าง");
        }
    }

    static List<double[]> levels(JSONArray a, boolean reverse)
        throws Exception {

        List<double[]> rows = new ArrayList<>();

        for (int i = 0; i < a.length(); i++) {
            JSONArray r = a.getJSONArray(i);
            double p = r.getDouble(0);
            double q = r.getDouble(1);

            if (p > 0 && q > 0 &&
                Double.isFinite(p) && Double.isFinite(q))
                rows.add(new double[]{p, q});
        }

        rows.sort((x, y) -> reverse
            ? Double.compare(y[0], x[0])
            : Double.compare(x[0], y[0]));

        return rows;
    }

    Book book(boolean bk, String coin) throws Exception {

        String url = bk
            ? "https://api.bitkub.com/api/v3/market/depth?sym="
                + coin + "_THB&lmt=20"
            : "https://api.binance.th/api/v1/depth?symbol="
                + coin + "THB&limit=20";

        JSONObject j = get(url);

        if (j.optInt("error", 0) != 0)
            throw new IOException("API error " + j.optInt("error"));

        if (j.optJSONObject("result") != null)
            j = j.getJSONObject("result");

        if (j.optJSONObject("data") != null)
            j = j.getJSONObject("data");

        return new Book(j);
    }

    Map<String,JSONArray> bitkubNetworks(String key, String secret) throws Exception {
        if (key.isEmpty() || secret.isEmpty()) throw new IOException("ยังไม่ได้ตั้ง API Bitkub");
        HttpURLConnection conn = (HttpURLConnection)new URL("https://api.bitkub.com/api/v3/servertime").openConnection();
        conn.setConnectTimeout(7000); conn.setReadTimeout(7000);
        String ts;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) { ts = r.readLine().trim(); } finally { conn.disconnect(); }
        if (!ts.matches("[0-9]{13}")) throw new IOException("เวลา API ไม่ถูกต้อง");
        String path = "/api/v4/crypto/coins";
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"));
        StringBuilder sig = new StringBuilder();
        for (byte v : mac.doFinal((ts+"GET"+path).getBytes("UTF-8"))) sig.append(String.format(Locale.US,"%02x",v & 255));
        Map<String,String> headers = new HashMap<>();
        headers.put("X-BTK-APIKEY",key); headers.put("X-BTK-TIMESTAMP",ts); headers.put("X-BTK-SIGN",sig.toString());
        JSONObject j = get("https://api.bitkub.com"+path,headers);
        if (!"0".equals(j.optString("code"))) throw new IOException("Bitkub metadata error");
        Map<String,JSONArray> m = new HashMap<>();
        JSONArray items = j.getJSONObject("data").getJSONArray("items");
        for(int i=0;i<items.length();i++){ JSONObject o=items.getJSONObject(i); m.put(o.getString("symbol"),o.getJSONArray("networks")); }
        return m;
    }
    JSONArray binanceNetworks(String coin) throws Exception {
        JSONObject j=get("https://www.binance.th/bapi/capital/v2/public/capital/config/getOne?coin="+coin);
        if (!"000000".equals(j.optString("code"))) throw new IOException("Binance metadata error");
        return j.getJSONArray("data");
    }
    static class Transfer { double fee; String network; Transfer(double f,String n){fee=f;network=n;} }
    static Transfer route(JSONArray bk, JSONArray bn, boolean fromBk, double qty) throws Exception {
        if(bk==null || bn==null) throw new IOException("ยังไม่ทราบเครือข่ายร่วม");
        Transfer best=null;
        for(int i=0;i<bk.length();i++) for(int j=0;j<bn.length();j++) {
            JSONObject x=bk.getJSONObject(i), y=bn.getJSONObject(j);
            String n=x.optString("network");
            if(n.isEmpty() || !n.equals(y.optString("network")) || y.optBoolean("busy",true)) continue;
            if(fromBk ? !(x.optBoolean("withdraw_enable") && y.optBoolean("depositEnable")) : !(y.optBoolean("withdrawEnable") && x.optBoolean("deposit_enable"))) continue;
            double f=fromBk?x.optDouble("withdraw_fee",Double.NaN):y.optDouble("withdrawFee",Double.NaN);
            double min=fromBk?x.optDouble("withdraw_min",Double.NaN):y.optDouble("withdrawMin",Double.NaN);
            double dep=fromBk?(y.has("depositFee") && y.isNull("depositFee")?0:y.optDouble("depositFee",Double.NaN)):0;
            if(!Double.isFinite(f)||!Double.isFinite(min)||!Double.isFinite(dep)||f<0||min<0||dep<0||qty-f-dep<min||qty<=f+dep) continue;
            if(fromBk && qty-f-dep<y.optDouble("depositDust",Double.POSITIVE_INFINITY)) continue;
            if(!fromBk && qty>y.optDouble("withdrawMax",0)) continue;
            if(best==null||f+dep<best.fee) best=new Transfer(f+dep,n);
        }
        if(best==null) throw new IOException("ไม่พบเครือข่ายที่ยืนยันได้");
        return best;
    }
    static double acquired(Book a,double cash){double q=0;for(double[] r:a.asks){double v=Math.min(cash,r[0]*r[1]);q+=v/r[0];cash-=v;}return q;}
    static double net(Book a, Book b, double cash,
                      double fa, double fb, double extra, double transferFee)
                      throws Exception {

        if (Math.abs(a.at - b.at) > 3000 ||
            SystemClock.elapsedRealtime() -
            Math.min(a.at, b.at) > 10000)
            throw new IOException("ข้อมูลไม่พร้อมกัน");

        double left = cash;
        double q = 0;

        for (double[] r : a.asks) {
            double v = Math.min(left, r[0] * r[1]);
            q += v / r[0];
            left -= v;
        }

        if (left > 0.00001)
            throw new IOException("ปริมาณซื้อไม่พอ");

        q -= transferFee;
        if(q<=0) throw new IOException("ค่าโอนสูงเกินจำนวนเหรียญ");
        double revenue = 0;

        for (double[] r : b.bids) {
            double v = Math.min(q, r[1]);
            q -= v;
            revenue += v * r[0];
        }

        if (q > 1e-10)
            throw new IOException("ปริมาณขายไม่พอ");

        return revenue * (1 - fb) -
               cash * (1 + fa) - extra;
    }

    static class Opportunity {
        String coin, buy, sell;
        double profit, roi, ask, bid;
        long time;
        double before, transferCost;
        String network = "ไม่ทราบ";
        boolean verified;

        Opportunity(String c, String x, String y,
                    double p, double r, double a, double b) {
            coin = c;
            buy = x;
            sell = y;
            profit = p;
            roi = r;
            ask = a;
            bid = b;
            time = System.currentTimeMillis();
        }
    }

    String money(double n) {
        return String.format(Locale.US, "%+,.2f", n);
    }

    void alert() {
        if (!sound.isChecked() || !active) return;

        long now = SystemClock.elapsedRealtime();

        if (now - lastAlert < 30000) return;
        lastAlert = now;

        try {
            ToneGenerator tone =
                new ToneGenerator(AudioManager.STREAM_MUSIC, 80);

            tone.startTone(ToneGenerator.TONE_PROP_ACK, 500);
            h.postDelayed(() -> tone.release(), 700);
        } catch(Exception ignored) {}
    }

    void runScan() {

        if (busy || !active) return;

        final double c, a, b, e;

        try {
            c = Double.parseDouble(capital.getText().toString());
            a = Double.parseDouble(fee1.getText().toString()) / 100;
            b = Double.parseDouble(fee2.getText().toString()) / 100;
            e = Double.parseDouble(buffer.getText().toString());

            if (!(c > 0 && c <= 1e7 &&
                  a >= 0 && a <= .05 &&
                  b >= 0 && b <= .05 &&
                  e >= 0 && e <= 1e5))
                throw new Exception();

        } catch(Exception ex) {
            status.setText("กรุณาตรวจสอบเงินทุนและค่าธรรมเนียม");
            return;
        }

        final String key=apiKey.getText().toString().trim(), secret=apiSecret.getText().toString().trim();
        busy = true;
        scan.setEnabled(false);

        best.setText("--");
        status.setText("กำลังค้นหาคู่เหรียญและสแกน...");
        results.setAlpha(0.45f);

        worker.submit(() -> {

            List<Opportunity> found = new ArrayList<>();
            List<String> errors = new ArrayList<>();

            int total = 0;
            int checked = 0;

            try {
                discover();
                Map<String,JSONArray> bkMeta = Collections.emptyMap();
                try { bkMeta=bitkubNetworks(key,secret); } catch(Exception ex){errors.add("Bitkub: ไม่สามารถตรวจสอบข้อมูลโอน (ตรวจ API Key/Secret)");}
                total = coins.size();

                for (String coin : coins) {

                    if (Thread.currentThread().isInterrupted())
                        break;

                    final int current = ++checked;
                    final int count = total;

                    runOnUiThread(() ->
                        status.setText(
                            "กำลังสแกน " + coin +
                            " (" + current + "/" + count + ")"));

                    JSONArray bnMeta=null;
                    try { bnMeta=binanceNetworks(coin); } catch(Exception ex){errors.add(coin+": Binance TH ข้อมูลเครือข่ายไม่พร้อม");}
                    try {
                        Future<Book> f =
                            pair.submit(() -> book(true, coin));

                        Future<Book> g =
                            pair.submit(() -> book(false, coin));

                        Book x = f.get();
                        Book y = g.get();

                        for (int k = 0; k < 2; k++) {

                            Book buyBook = k == 0 ? x : y;
                            Book sellBook = k == 0 ? y : x;

                            try {
                                double p = net(
                                    buyBook, sellBook, c,
                                    k == 0 ? a : b,
                                    k == 0 ? b : a, e, 0);

                                Opportunity op = new Opportunity(
                                    coin,
                                    k == 0 ? "Bitkub" : "Binance TH",
                                    k == 0 ? "Binance TH" : "Bitkub",
                                    p, p / c * 100,
                                    buyBook.asks.get(0)[0],
                                    sellBook.bids.get(0)[0]);
                                op.before=p+e;
                                try {
                                    Transfer t=route(bkMeta.get(coin),bnMeta,k==0,acquired(buyBook,c));
                                    op.profit=net(buyBook,sellBook,c,k==0?a:b,k==0?b:a,e,t.fee);
                                    op.roi=op.profit/c*100; op.network=t.network; op.transferCost=p-op.profit; op.verified=true;
                                } catch(Exception ex){ op.network=ex.getMessage(); }
                                found.add(op);

                            } catch(Exception ex) {
                                errors.add(coin + ": " + ex.getMessage());
                            }
                        }

                    } catch(Exception ex) {

                        Throwable cause =
                            ex.getCause() == null ? ex : ex.getCause();

                        String msg = String.valueOf(cause.getMessage());

                        errors.add(coin + ": " + msg);

                        if (msg.contains("429") || msg.contains("418")) {
                            errors.add("พักการสแกนตามข้อจำกัด API");
                            Thread.sleep(30000);
                            break;
                        }
                    }

                    Thread.sleep(350);
                }

            } catch(Exception ex) {
                errors.add(String.valueOf(ex.getMessage()));
            }

            found.sort((x, y) ->
                x.verified != y.verified ? (x.verified ? -1 : 1) : Double.compare(y.profit, x.profit));

            final int n = total;
            final int attempted = checked;

            runOnUiThread(() -> showResults(
                found, errors, n, attempted));
        });
    }

    void showResults(List<Opportunity> rows,
                     List<String> errors,
                     int total, int attempted) {

        if (isDestroyed()) return;

        busy = false;
        scan.setEnabled(true);
        results.setAlpha(1f);
        results.removeAllViews();

        int positive = 0;
        Set<String> successful = new HashSet<>();
        StringBuilder csv = new StringBuilder();

        for (Opportunity o : rows) {
            successful.add(o.coin);
            if (o.verified && o.profit > 0) positive++;

            if(o.verified) record = Math.max(record, o.profit);

            csv.append(o.time).append(",")
                .append(o.coin).append(",")
                .append(o.buy).append(",")
                .append(o.sell).append(",")
                .append(o.profit).append(",")
                .append(o.roi).append(",").append(o.verified).append(",").append(o.verified?o.network:"UNKNOWN").append("\n");
        }

        rounds++;
        hits += positive;

        if (!rows.isEmpty() && rows.get(0).verified) {
            double p = rows.get(0).profit;

            best.setText(money(p) + " บาท");
            best.setTextColor(p > 0 ? GREEN : RED);
        } else {
            best.setText("-- ยังไม่ยืนยันค่าโอน");
            best.setTextColor(GRAY);
        }

        stats.setText(
            "คู่เหรียญร่วมกัน: " + total +
            "\nสแกนได้: " + successful.size() + "/" + attempted +
            "\nเส้นทางกำไรบวก: " + positive +
            "\nจำนวนรอบสะสม: " + rounds +
            "\nพบกำไรจำลองสะสม: " + hits +
            "\nสถิติสูงสุด: " +
            (Double.isFinite(record) ? money(record) : "--") +
            " บาท");

        status.setText("จบรอบ: " + new Date() +
            "\nราคาของแต่ละเหรียญอาจต่างเวลากัน");

        for (Opportunity o : rows) {

            LinearLayout item = card(results);

            TextView name = text(item, o.coin + "/THB", 20, WHITE);
            name.setTypeface(null, Typeface.BOLD);

            text(item, o.buy + " → " + o.sell, 14, GRAY);

            TextView profit = text(item,
                (o.verified ? money(o.profit) : "ยังไม่ยืนยัน") + " บาท",
                24, !o.verified ? GRAY : o.profit > 0 ? GREEN : RED);

            profit.setTypeface(null, Typeface.BOLD);

            text(item, String.format(Locale.US,
                "%+.3f%% ("+(o.verified?"หลังค่าโอน":"ก่อนค่าโอน")+")", o.roi), 16,
                o.profit > 0 ? GREEN : RED);

            text(item, String.format(Locale.US,
                "ซื้อ: %.8f\nขาย: %.8f",
                o.ask, o.bid), 12, GRAY);

            text(item,"หลังค่าซื้อขาย ก่อนค่าโอน/สำรอง: "+money(o.before)+" บาท",12,GRAY);
            text(item,o.verified ? "เครือข่าย: "+o.network+" | ค่าโอนประมาณ "+money(o.transferCost)+" บาท" : "ค่าโอนยังไม่ยืนยัน: "+o.network,12,GRAY);
            text(item, new Date(o.time).toString(), 11, GRAY);
        }

        if (!errors.isEmpty()) {
            LinearLayout errorCard = card(results);
            text(errorCard, "รายการที่ตรวจสอบไม่สำเร็จ", 15, RED);

            for (String error : errors)
                text(errorCard, error, 12, GRAY);
        }

        text(results,
            "ผลจำลองก่อนภาษี ไม่รับประกันการซื้อขายจริง " +
            "ไม่ตรวจยอดเงินหรือเวลาที่โอนจริง ต้องยืนยันเครือข่ายและข้อจำกัดในตลาดก่อนใช้งาน",
            12, GRAY);

        history += csv.toString();

        if (history.length() > 500000) {
            int cut = history.indexOf('\n', history.length() - 400000);
            if (cut >= 0) history = history.substring(cut + 1);
        }

        getPreferences(0).edit()
            .putString("history_v4", history)
            .putLong("rounds_v4", rounds)
            .putLong("hits_v4", hits)
            .putString("record_v4", Double.toString(record))
            .apply();

        if (positive > 0) alert();
    }
}
