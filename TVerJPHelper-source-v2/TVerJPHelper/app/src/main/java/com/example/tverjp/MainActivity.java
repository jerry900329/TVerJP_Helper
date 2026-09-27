package com.example.tverjp;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.blinkt.openvpn.api.IOpenVPNAPIService;
import de.blinkt.openvpn.api.IOpenVPNStatusCallback;

public class MainActivity extends Activity {
    private static final String VPN_PACKAGE = "de.blinkt.openvpn";
    private static final String TVER_PACKAGE = "jp.hamitv.hamiand1";
    private static final String VPN_GATE_API = "https://www.vpngate.net/api/iphone/";
    private static final int REQ_API = 1001;
    private static final int REQ_VPN = 1002;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button connectButton;
    private Button disconnectButton;
    private IOpenVPNAPIService vpn;
    private boolean bound = false;
    private boolean callbackRegistered = false;
    private boolean attemptActive = false;
    private int attemptToken = 0;
    private int candidateIndex = 0;
    private List<Candidate> ranked = new ArrayList<>();

    private final IOpenVPNStatusCallback callback = new IOpenVPNStatusCallback.Stub() {
        @Override public void newStatus(String uuid, String state, String message, String level) {
            main.post(() -> handleVpnStatus(state, message, level));
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            vpn = IOpenVPNAPIService.Stub.asInterface(service);
            bound = true;
            try {
                Intent permission = vpn.prepare(getPackageName());
                if (permission != null) {
                    setStatus("第一次設定：請允許 TVer JP 控制 OpenVPN for Android");
                    startActivityForResult(permission, REQ_API);
                } else {
                    afterApiPermission();
                }
            } catch (RemoteException e) {
                fail("無法取得 OpenVPN 控制權：" + e.getMessage());
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            vpn = null;
            setStatus("OpenVPN for Android 服務已中斷");
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        bindOpenVpn();
    }

    private void buildUi() {
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("TVer JP");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setText("正在準備…");
        status.setTextSize(17);
        status.setPadding(0, pad, 0, pad);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        connectButton = new Button(this);
        connectButton.setText("重新挑選日本 VPN 並開 TVer");
        connectButton.setOnClickListener(v -> startSelection());
        connectButton.setEnabled(false);
        root.addView(connectButton, new LinearLayout.LayoutParams(-1, -2));

        disconnectButton = new Button(this);
        disconnectButton.setText("斷開 VPN");
        disconnectButton.setOnClickListener(v -> disconnectVpn());
        root.addView(disconnectButton, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(this);
        note.setText("資料來源：筑波大學 VPN Gate 官方 API。\n只挑 Japan (JP)，依 Ping、Speed、Uptime、Sessions 與手機實測端點延遲排序。");
        note.setTextSize(13);
        note.setPadding(0, pad, 0, 0);
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    private void bindOpenVpn() {
        Intent service = new Intent("de.blinkt.openvpn.api.IOpenVPNAPIService");
        service.setPackage(VPN_PACKAGE);
        boolean ok = bindService(service, connection, Context.BIND_AUTO_CREATE);
        if (!ok) {
            setStatus("尚未安裝 OpenVPN for Android");
            connectButton.setText("安裝 OpenVPN for Android");
            connectButton.setEnabled(true);
            connectButton.setOnClickListener(v -> openPlayStore(VPN_PACKAGE));
        }
    }

    private void prepareVpnPermission() {
        if (vpn == null) return;
        try {
            Intent permission = vpn.prepareVPNService();
            if (permission != null) {
                setStatus("第一次設定：請允許建立 VPN 連線");
                startActivityForResult(permission, REQ_VPN);
            } else {
                readyAndStart();
            }
        } catch (RemoteException e) {
            fail("VPN 權限檢查失敗：" + e.getMessage());
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) {
            fail("權限未允許，無法自動連線");
            return;
        }
        if (requestCode == REQ_API) afterApiPermission();
        else if (requestCode == REQ_VPN) readyAndStart();
    }

    private void afterApiPermission() {
        if (vpn == null) return;
        try {
            if (!callbackRegistered) {
                vpn.registerStatusCallback(callback);
                callbackRegistered = true;
            }
            prepareVpnPermission();
        } catch (RemoteException e) {
            fail("OpenVPN API 初始化失敗：" + e.getMessage());
        }
    }

    private void readyAndStart() {
        connectButton.setEnabled(true);
        connectButton.setText("重新挑選日本 VPN 並開 TVer");
        startSelection();
    }

    private void startSelection() {
        if (vpn == null) {
            bindOpenVpn();
            return;
        }
        connectButton.setEnabled(false);
        attemptActive = false;
        candidateIndex = 0;
        attemptToken++;
        setStatus("正在取得 VPN Gate 最新日本節點…");
        worker.execute(() -> {
            try {
                List<Candidate> all = fetchCandidates();
                if (all.isEmpty()) throw new Exception("目前找不到可用的日本 OpenVPN 節點");

                List<Candidate> filtered = new ArrayList<>();
                for (Candidate c : all) {
                    if (c.pingMs > 0 && c.pingMs <= 180 && c.speedBps >= 20_000_000L && c.uptimeMs >= 30L * 60L * 1000L) {
                        filtered.add(c);
                    }
                }
                if (filtered.size() < 3) filtered = all;

                for (Candidate c : filtered) c.preScore = preScore(c);
                filtered.sort((a, b) -> Double.compare(b.preScore, a.preScore));
                if (filtered.size() > 8) filtered = new ArrayList<>(filtered.subList(0, 8));

                main.post(() -> setStatus("已篩出候選節點，正在從這支手機測試連線延遲…"));
                for (Candidate c : filtered) {
                    c.endpoint = parseEndpoint(c.config, c.ip);
                    if (c.endpoint != null && c.endpoint.tcp) {
                        c.actualRttMs = medianTcpRtt(c.ip, c.endpoint.port);
                    } else {
                        c.actualRttMs = -1;
                    }
                    c.finalScore = finalScore(c);
                }

                filtered.sort((a, b) -> Double.compare(b.finalScore, a.finalScore));
                ranked = filtered;
                main.post(() -> {
                    candidateIndex = 0;
                    connectCurrentCandidate();
                });
            } catch (Exception e) {
                main.post(() -> fail("選線失敗：" + e.getMessage()));
            }
        });
    }

    private List<Candidate> fetchCandidates() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(VPN_GATE_API).openConnection();
        conn.setConnectTimeout(7000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "TVerJPHelper/0.1 Android");
        int code = conn.getResponseCode();
        if (code != 200) throw new Exception("VPN Gate API HTTP " + code);

        List<Candidate> result = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("*") || line.startsWith("#") || line.startsWith("$")) continue;
                List<String> f = parseCsvLine(line);
                if (f.size() < 15 || !"JP".equalsIgnoreCase(f.get(6))) continue;
                String b64 = f.get(14).trim();
                if (b64.isEmpty()) continue;
                try {
                    Candidate c = new Candidate();
                    c.hostName = f.get(0);
                    c.ip = f.get(1);
                    c.officialScore = parseLong(f.get(2), 0);
                    c.pingMs = (int) parseLong(f.get(3), 999);
                    c.speedBps = parseLong(f.get(4), 0);
                    c.sessions = (int) parseLong(f.get(7), 999);
                    c.uptimeMs = parseLong(f.get(8), 0);
                    c.config = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
                    result.add(c);
                } catch (Exception ignored) { }
            }
        } finally {
            conn.disconnect();
        }
        return result;
    }

    private void connectCurrentCandidate() {
        if (vpn == null) return;
        if (candidateIndex >= ranked.size()) {
            fail("候選日本節點都無法建立 VPN，請稍後再試");
            return;
        }
        Candidate c = ranked.get(candidateIndex);
        attemptActive = true;
        final int myToken = ++attemptToken;
        String detail = String.format(Locale.US,
                "嘗試 %d/%d：%s\n官方 Ping %d ms · %.0f Mbps · uptime %.1f h%s",
                candidateIndex + 1, ranked.size(), c.hostName, c.pingMs,
                c.speedBps / 1_000_000.0, c.uptimeMs / 3_600_000.0,
                c.actualRttMs > 0 ? String.format(Locale.US, " · 手機端 %.0f ms", c.actualRttMs) : "");
        setStatus(detail);
        try {
            vpn.disconnect();
        } catch (Exception ignored) { }
        main.postDelayed(() -> {
            try {
                if (vpn != null) vpn.startVPN(c.config);
            } catch (RemoteException e) {
                tryNext("啟動失敗");
                return;
            }
            main.postDelayed(() -> {
                if (attemptActive && myToken == attemptToken) tryNext("連線逾時");
            }, 14_000);
        }, 350);
    }

    private void handleVpnStatus(String state, String message, String level) {
        if (state == null) return;
        if ("CONNECTED".equalsIgnoreCase(state) || "LEVEL_CONNECTED".equalsIgnoreCase(level)) {
            attemptActive = false;
            attemptToken++;
            Candidate c = candidateIndex < ranked.size() ? ranked.get(candidateIndex) : null;
            if (c != null) {
                setStatus(String.format(Locale.US,
                        "已連上日本：%s\n官方 Ping %d ms · %.0f Mbps%s\n正在開啟 TVer…",
                        c.hostName, c.pingMs, c.speedBps / 1_000_000.0,
                        c.actualRttMs > 0 ? String.format(Locale.US, " · 手機端 %.0f ms", c.actualRttMs) : ""));
            } else {
                setStatus("VPN 已連線，正在開啟 TVer…");
            }
            connectButton.setEnabled(true);
            main.postDelayed(this::openTVer, 1200);
            return;
        }

        if (!attemptActive) return;
        String s = state.toUpperCase(Locale.US);
        String l = level == null ? "" : level.toUpperCase(Locale.US);
        if (s.contains("AUTH_FAILED") || s.equals("EXITING") || l.contains("AUTH_FAILED")) {
            tryNext("VPN 回報失敗");
        }
    }

    private void tryNext(String why) {
        if (!attemptActive) return;
        attemptActive = false;
        attemptToken++;
        candidateIndex++;
        if (candidateIndex < ranked.size()) {
            setStatus(why + "，自動改試下一台…");
            main.postDelayed(this::connectCurrentCandidate, 500);
        } else {
            fail("所有候選節點都失敗；VPN Gate 節點變動很快，請按一次重新挑選");
        }
    }

    private void disconnectVpn() {
        try {
            attemptActive = false;
            attemptToken++;
            if (vpn != null) vpn.disconnect();
            setStatus("VPN 已斷開");
            connectButton.setEnabled(true);
        } catch (RemoteException e) {
            fail("斷線失敗：" + e.getMessage());
        }
    }

    private void openTVer() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(TVER_PACKAGE);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
        } else {
            Toast.makeText(this, "尚未安裝 TVer", Toast.LENGTH_LONG).show();
            openPlayStore(TVER_PACKAGE);
        }
    }

    private void openPlayStore(String pkg) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
        } catch (Exception e) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
        }
    }

    private static Endpoint parseEndpoint(String config, String fallbackIp) {
        String proto = "";
        String host = fallbackIp;
        int port = -1;
        for (String raw : config.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            String[] p = line.split("\\s+");
            if (p.length >= 2 && "proto".equalsIgnoreCase(p[0])) proto = p[1].toLowerCase(Locale.US);
            if (p.length >= 3 && "remote".equalsIgnoreCase(p[0])) {
                host = p[1];
                try { port = Integer.parseInt(p[2]); } catch (Exception ignored) { }
                if (p.length >= 4) proto = p[3].toLowerCase(Locale.US);
                break;
            }
        }
        if (port <= 0) return null;
        Endpoint e = new Endpoint();
        e.host = host;
        e.port = port;
        e.tcp = proto.contains("tcp");
        return e;
    }

    private static double medianTcpRtt(String ip, int port) {
        List<Double> times = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            long start = System.nanoTime();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ip, port), 900);
                double ms = (System.nanoTime() - start) / 1_000_000.0;
                times.add(ms);
            } catch (Exception ignored) { }
        }
        if (times.isEmpty()) return -1;
        Collections.sort(times);
        return times.get(times.size() / 2);
    }

    private static double preScore(Candidate c) {
        double ping = 1.0 - clamp(c.pingMs / 180.0);
        double speed = clamp(Math.log10(c.speedBps / 1_000_000.0 + 1) / Math.log10(1001));
        double uptime = clamp(Math.log10(c.uptimeMs / 60_000.0 + 1) / Math.log10(10081));
        double load = 1.0 - clamp(c.sessions / 150.0);
        return 0.45 * ping + 0.30 * speed + 0.20 * uptime + 0.05 * load;
    }

    private static double finalScore(Candidate c) {
        double actual;
        if (c.actualRttMs > 0) actual = 1.0 - clamp(c.actualRttMs / 250.0);
        else actual = 0.55 * (1.0 - clamp(c.pingMs / 180.0));
        double speed = clamp(Math.log10(c.speedBps / 1_000_000.0 + 1) / Math.log10(1001));
        double uptime = clamp(Math.log10(c.uptimeMs / 60_000.0 + 1) / Math.log10(10081));
        double load = 1.0 - clamp(c.sessions / 150.0);
        return 0.50 * actual + 0.25 * speed + 0.15 * uptime + 0.10 * load;
    }

    private static double clamp(double x) { return Math.max(0, Math.min(1, x)); }

    private static long parseLong(String s, long fallback) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return fallback; }
    }

    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private void setStatus(String s) { status.setText(s); }

    private void fail(String s) {
        attemptActive = false;
        setStatus(s);
        connectButton.setEnabled(true);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (vpn != null && callbackRegistered) {
            try { vpn.unregisterStatusCallback(callback); } catch (Exception ignored) { }
        }
        if (bound) {
            try { unbindService(connection); } catch (Exception ignored) { }
        }
        worker.shutdownNow();
    }

    private static class Endpoint {
        String host;
        int port;
        boolean tcp;
    }

    private static class Candidate {
        String hostName;
        String ip;
        int pingMs;
        long speedBps;
        int sessions;
        long uptimeMs;
        long officialScore;
        String config;
        Endpoint endpoint;
        double actualRttMs = -1;
        double preScore;
        double finalScore;
    }
}
