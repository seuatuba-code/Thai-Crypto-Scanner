package th.cryptoscanner;

import android.app.*;
import android.os.*;
import android.widget.*;
import android.view.*;
import android.content.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    EditText capital, fee1, fee2, buffer;
    TextView result;
    Button scan;
    Handler h = new Handler(Looper.getMainLooper());
    boolean active = false, busy = false;
    ExecutorService worker = Executors.newSingleThreadExecutor();
    String history = "";

    EditText field(LinearLayout l, String label, String val) {
        TextView t = new TextView(this);
        t.setText(label);
        l.addView(t);
        EditText e = new EditText(this);
        e.setInputType(8194);
        e.setText(val);
        l.addView(e);
        return e;
    }

    public void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView s = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(1);
        l.setPadding(24, 90, 24, 48);
        s.addView(l);
        setContentView(s);

        TextView title = new TextView(this);
        title.setText("THAI CRYPTO SCANNER V2.2\nBitkub × Binance TH\nโหมดจำลองเท่านั้น");
        title.setTextSize(24);
        l.addView(title);

        capital = field(l, "เงินซื้อจำลอง (บาท)", "10000");
        fee1 = field(l, "ค่าธรรมเนียม Bitkub (%)", "0.25");
        fee2 = field(l, "ค่าธรรมเนียม Binance TH (%)", "0.25");
        buffer = field(l, "ต้นทุนสำรอง (บาท)", "50");

        scan = new Button(this);
        scan.setText("สแกนตลาด");
        l.addView(scan);
        scan.setOnClickListener(v -> runScan());

        result = new TextView(this);
        result.setText("รอการสแกน");
        l.addView(result);

        Button share = new Button(this);
        share.setText("แชร์ประวัติ CSV");
        l.addView(share);
        share.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT,
                "time,coin,buy,sell,net,roi\n" + history);
            startActivity(Intent.createChooser(i, "แชร์ประวัติ"));
        });
        history = getPreferences(0).getString("history", "");
    }

    Runnable tick = new Runnable() {
        public void run() {
            if (active) {
                runScan();
                h.postDelayed(this, 60000);
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
        super.onDestroy();
    }

    JSONObject get(String u) throws Exception {
        HttpURLConnection c =
            (HttpURLConnection) new URL(u).openConnection();

        c.setRequestMethod("GET");
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "ThaiCryptoScanner/2.2");

        try {
            int status = c.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                ? c.getInputStream() : c.getErrorStream();

            String body = "";
            if (stream != null) {
                try (InputStream in = stream;
                     ByteArrayOutputStream o = new ByteArrayOutputStream()) {
                    byte[] bytes = new byte[4096];
                    int n;
                    while ((n = in.read(bytes)) != -1)
                        o.write(bytes, 0, n);
                    body = o.toString("UTF-8");
                }
            }

            if (status < 200 || status >= 300)
                throw new IOException("HTTP " + status + " " +
                    body.substring(0, Math.min(160, body.length())));

            return new JSONObject(body);
        } finally {
            c.disconnect();
        }
    }

    static class Book {
        List<double[]> asks, bids;
        long at;

        Book(JSONObject j) throws Exception {
            asks = levels(j.getJSONArray("asks"), false);
            bids = levels(j.getJSONArray("bids"), true);
            if (asks.isEmpty() || bids.isEmpty())
                throw new IOException("Order book ว่าง");
            at = SystemClock.elapsedRealtime();
        }
    }

    static List<double[]> levels(JSONArray a, boolean reverse)
        throws Exception {

        List<double[]> l = new ArrayList<>();

        for (int i = 0; i < a.length(); i++) {
            JSONArray r = a.getJSONArray(i);
            double p = r.getDouble(0);
            double q = r.getDouble(1);

            if (p > 0 && q > 0 &&
                Double.isFinite(p) && Double.isFinite(q))
                l.add(new double[]{p, q});
        }

        l.sort((x, y) -> reverse
            ? Double.compare(y[0], x[0])
            : Double.compare(x[0], y[0]));

        return l;
    }

    Book book(boolean bk, String coin) throws Exception {
        String market = bk ? "Bitkub" : "Binance TH";

        try {
            String url;

            if (bk) {
                url = "https://api.bitkub.com/api/v3/market/depth?sym="
                    + coin.toLowerCase(Locale.US) + "_thb&lmt=20";
            } else {
                url = "https://api.binance.th/api/v1/depth?symbol="
                    + coin + "THB&limit=20";
            }

            JSONObject j = get(url);

            if (j.has("error") && j.optInt("error", -1) != 0)
                throw new IOException("API error: " + j.toString());

            if (j.has("code") && !j.has("asks"))
                throw new IOException("API code: " + j.optString("code")
                    + " " + j.optString("msg"));

            if (j.optJSONObject("result") != null)
                j = j.getJSONObject("result");

            if (j.optJSONObject("data") != null)
                j = j.getJSONObject("data");

            return new Book(j);

        } catch (Exception ex) {
            throw new IOException(market + ": " + ex.getMessage(), ex);
        }
    }

    static double net(Book a, Book b, double cash,
                      double fa, double fb, double extra)
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

        double rev = 0;

        for (double[] r : b.bids) {
            double v = Math.min(q, r[1]);
            q -= v;
            rev += v * r[0];
        }

        if (q > 1e-10)
            throw new IOException("ปริมาณขายไม่พอ");

        return rev * (1 - fb) - cash * (1 + fa) - extra;
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

        } catch (Exception ex) {
            result.setText("กรุณาตรวจสอบจำนวนเงินและค่าธรรมเนียม");
            return;
        }

        busy = true;
        scan.setEnabled(false);
        result.setText("กำลังดึงข้อมูลตลาด...");

        worker.submit(() -> {
            StringBuilder out = new StringBuilder();
            StringBuilder csv = new StringBuilder();
            ExecutorService pair = Executors.newFixedThreadPool(2);

            try {
                for (String coin : new String[]{"BTC","ETH","XRP","SOL"}) {

                    Future<Book> f = pair.submit(() -> book(true, coin));
                    Future<Book> g = pair.submit(() -> book(false, coin));

                    Book x = null, y = null;
                    out.append("\n========== " + coin + " ==========\n");

                    try {
                        x = f.get();
                        out.append(String.format(Locale.US,
                            "Bitkub ซื้อ %.2f | ขาย %.2f\n",
                            x.asks.get(0)[0], x.bids.get(0)[0]));
                    } catch (Exception ex) {
                        out.append("Bitkub ERROR: " +
                            ex.getMessage() + "\n");
                    }

                    try {
                        y = g.get();
                        out.append(String.format(Locale.US,
                            "Binance TH ซื้อ %.2f | ขาย %.2f\n",
                            y.asks.get(0)[0], y.bids.get(0)[0]));
                    } catch (Exception ex) {
                        out.append("Binance TH ERROR: " +
                            ex.getMessage() + "\n");
                    }

                    if (x == null || y == null) continue;

                    for (int k = 0; k < 2; k++) {
                        try {
                            double n = net(
                                k == 0 ? x : y,
                                k == 0 ? y : x,
                                c,
                                k == 0 ? a : b,
                                k == 0 ? b : a,
                                e);

                            String buy = k == 0 ? "Bitkub" : "Binance TH";
                            String sell = k == 0 ? "Binance TH" : "Bitkub";

                            out.append(String.format(Locale.US,
                                "%s → %s\nสุทธิ %+.2f บาท (%.3f%%)\n\n",
                                buy, sell, n, n / c * 100));

                            csv.append(System.currentTimeMillis() +
                                "," + coin + "," + buy + "," + sell +
                                "," + n + "," + n / c * 100 + "\n");

                        } catch (Exception ex) {
                            out.append(ex.getMessage() + "\n");
                        }
                    }
                }
            } finally {
                pair.shutdownNow();
            }

            runOnUiThread(() -> {
                busy = false;
                scan.setEnabled(true);

                result.setText("สแกนล่าสุด " + new Date() + "\n" +
                    out + "\nค่าประมาณก่อนภาษี ไม่ใช่ผลซื้อขายจริง");

                history += csv.toString();

                if (history.length() > 300000)
                    history = history.substring(
                        history.indexOf('\n', history.length() - 250000) + 1);

                getPreferences(0).edit()
                    .putString("history", history).apply();
            });
        });
    }
}
