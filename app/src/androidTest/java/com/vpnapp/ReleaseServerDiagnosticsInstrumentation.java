package com.vpnapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.URL;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

/** Framework-only test against the minified Release APK; direct traffic must use Android's VPN. */
public class ReleaseServerDiagnosticsInstrumentation extends Instrumentation {
    private Bundle args;
    private Activity activity;
    private SharedPreferences profiles, settings;
    private final JSONArray rounds = new JSONArray();
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); args = arguments; start(); }
    private void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String label) {
        if (node == null) return null;
        if (label.equals(node.getText() == null ? "" : node.getText().toString().trim()) ||
            label.equals(node.getHintText() == null ? "" : node.getHintText().toString().trim()) ||
            label.equals(node.getContentDescription() == null ? "" : node.getContentDescription().toString().trim())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = find(node.getChild(i), label);
            if (child != null) return child;
        }
        return null;
    }
    private AccessibilityNodeInfo find(String label) { return find(getUiAutomation().getRootInActiveWindow(), label); }
    private void click(String label) {
        long end=SystemClock.elapsedRealtime()+5000;
        while(find(label)==null && SystemClock.elapsedRealtime()<end) SystemClock.sleep(100);
        AccessibilityNodeInfo node = find(label);
        require(node != null, "Missing control: " + label);
        while (node != null && !node.isClickable()) node = node.getParent();
        require(node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "Cannot click: " + label);
    }
    private boolean tryClick(String label) {
        AccessibilityNodeInfo node=find(label);
        while(node!=null&&!node.isClickable()) node=node.getParent();
        return node!=null&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    private void collectFields(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> fields) {
        if (node == null) return;
        if (node.isEditable()) fields.add(node);
        for (int i=0; i<node.getChildCount(); i++) collectFields(node.getChild(i), fields);
    }
    private void setField(int index, String value) {
        List<AccessibilityNodeInfo> fields = new ArrayList<>();
        collectFields(getUiAutomation().getRootInActiveWindow(), fields);
        require(fields.size() > index, "Missing input field");
        Bundle text = new Bundle(); text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        require(fields.get(index).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text), "Cannot set input field");
    }
    private void collectValues(AccessibilityNodeInfo node, Rect anchor, List<String> result) {
        if (node == null) return;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        if (bounds.top >= anchor.bottom-4 && bounds.top < anchor.bottom+100 &&
            Math.abs(bounds.centerX()-anchor.centerX()) < Math.max(anchor.width(),50) && node.getText()!=null)
            result.add(node.getText().toString());
        for (int i=0;i<node.getChildCount();i++) collectValues(node.getChild(i),anchor,result);
    }
    private List<String> values(String label) {
        List<String> result = new ArrayList<>(); AccessibilityNodeInfo node = find(label);
        if (node != null) { Rect anchor=new Rect(); node.getBoundsInScreen(anchor);
            collectValues(getUiAutomation().getRootInActiveWindow(),anchor,result); }
        return result;
    }
    private String shell(String command) throws Exception {
        try (ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);
             InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(fd);
             ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[2048]; int count;
            while ((count=input.read(buffer))!=-1) bytes.write(buffer,0,count);
            return bytes.toString("UTF-8").trim();
        }
    }
    private boolean vpnActive() {
        ConnectivityManager manager=getTargetContext().getSystemService(ConnectivityManager.class);
        Network network=manager.getActiveNetwork(); NetworkCapabilities caps=manager.getNetworkCapabilities(network);
        return caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }
    private boolean anyVpn() {
        ConnectivityManager manager=getTargetContext().getSystemService(ConnectivityManager.class);
        for (Network network:manager.getAllNetworks()) {
            NetworkCapabilities caps=manager.getNetworkCapabilities(network);
            if (caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }
    private void waitFor(String label, long milliseconds) throws Exception {
        long end=SystemClock.elapsedRealtime()+milliseconds;
        while (find(label)==null && SystemClock.elapsedRealtime()<end) Thread.sleep(100);
        require(find(label)!=null,"Missing final state/control: "+label);
    }
    private void closeNotificationShade() throws Exception {
        for(int attempt=0;attempt<24;attempt++) {
            AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root!=null && "com.vpnapp".contentEquals(root.getPackageName())) return;
            AccessibilityNodeInfo title=find("SecureVPN");
            if(title!=null && "com.vpnapp".contentEquals(title.getPackageName())) return;
            // Do not press Back while the activity is still starting over the launcher.
            if(find("Notification settings")!=null || find("Clear all")!=null) {
                shell("input keyevent 4"); Thread.sleep(500);
            } else Thread.sleep(250);
        }
        AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
        require(root!=null && "com.vpnapp".contentEquals(root.getPackageName()),"App is not visible after closing notification shade");
    }
    private File backup() { return new File(getTargetContext().getCacheDir(), "release-diagnostics-backup.private.json"); }
    private void saveBackup(String subscriptionId) throws Exception {
        JSONObject value=new JSONObject().put("profiles",profiles.getString("profiles_json","[]"))
            .put("test_subscription_id",subscriptionId)
            .put("selected",profiles.getString("selected_profile_id",""))
            .put("mtu",settings.getInt("mtu_size",1400))
            .put("bypass_iran",settings.getBoolean("bypass_iran",true))
            .put("bypass_private_ips",settings.getBoolean("bypass_private_ips",true))
            .put("buffer_size",settings.getInt("buffer_size",2097152)).put("tcp_no_delay",settings.getBoolean("tcp_no_delay",true))
            .put("excluded",new JSONArray(settings.getStringSet("excluded_apps",Collections.emptySet())));
        try (Writer writer=new OutputStreamWriter(new FileOutputStream(backup()),"UTF-8")) { writer.write(value.toString()); }
    }
    private JSONObject readBackup() throws Exception {
        try (Reader reader=new InputStreamReader(new FileInputStream(backup()),"UTF-8")) {
            StringBuilder text=new StringBuilder(); char[] buffer=new char[4096]; int n;
            while ((n=reader.read(buffer))!=-1) text.append(buffer,0,n);
            return new JSONObject(text.toString());
        }
    }
    private void launch() throws Exception {
        activity=startActivitySync(new Intent().setClassName("com.vpnapp","com.vpnapp.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        closeNotificationShade();
        waitFor("Not Connected",10000);
    }
    private void importSubscription(JSONObject output) throws Exception {
        String url=args.getString("url",""); require(!url.isEmpty(),"Subscription URL missing");
        String id=null; JSONArray subs=new JSONArray(profiles.getString("subscriptions_json","[]"));
        for (int i=0;i<subs.length();i++) if (url.equals(subs.getJSONObject(i).optString("url")) &&
            "Raha-XHTTP-Test".equals(subs.getJSONObject(i).optString("name"))) id=subs.getJSONObject(i).getString("id");
        if (id==null) {
            launch(); click("Add Sub"); Thread.sleep(200);
            setField(0,"Raha-XHTTP-Test"); setField(1,url); click("Add");
            long end=SystemClock.elapsedRealtime()+45000;
            while (id==null && SystemClock.elapsedRealtime()<end) {
                Thread.sleep(200); subs=new JSONArray(profiles.getString("subscriptions_json","[]"));
                for (int i=0;i<subs.length();i++) if (url.equals(subs.getJSONObject(i).optString("url")) &&
                    "Raha-XHTTP-Test".equals(subs.getJSONObject(i).optString("name"))) id=subs.getJSONObject(i).getString("id");
            }
        }
        require(id!=null,"Subscription import failed");
        int count=0; boolean france=false,switzerland=false,extra=false;
        long end=SystemClock.elapsedRealtime()+10000;
        do {
            count=0; JSONArray all=new JSONArray(profiles.getString("profiles_json","[]"));
            for (int i=0;i<all.length();i++) {
                JSONObject p=all.getJSONObject(i);
                if (!id.equals(p.optString("subscriptionId"))) continue;
                count++; String name=p.optString("name");
                if (name.contains("France")) france=true;
                if (name.contains("Switzerland")) switzerland=true;
                if ((name.contains("France")||name.contains("Switzerland")) && !p.optString("v2rayXhttpExtra").isEmpty()) extra=true;
            }
            if (france && switzerland) break;
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime()<end);
        require(france&&switzerland,"Target country profiles missing"); require(extra,"Imported XHTTP extra was lost");
        saveBackup(id); output.put("profiles_added",count).put("extra_preserved",extra);
    }
    private void prepare(JSONObject output) throws Exception {
        require(backup().exists(),"Run subscription import before diagnostics");
        JSONObject saved=readBackup(); JSONArray all=new JSONArray(saved.getString("profiles"));
        String needle=args.getString("country","France"), mode=args.getString("mode","auto");
        int mtu=Integer.parseInt(args.getString("mtu","1400")); String selected=null;
        for (int i=0;i<all.length();i++) {
            JSONObject p=all.getJSONObject(i);
            if (!saved.getString("test_subscription_id").equals(p.optString("subscriptionId")) ||
                !p.optString("name").contains(needle) || !"xhttp".equals(p.optString("v2rayNetwork"))) continue;
            selected=p.getString("id"); p.put("v2rayType",mode);
            // The app restores selections by name. Keep this test's subscription distinct from old duplicate names.
            p.put("name",p.getString("name")+" [Raha-XHTTP-Test]");
            String text=p.optString("v2rayXhttpExtra"); JSONObject extra=text.isEmpty()?new JSONObject():new JSONObject(text);
            if (extra.has("mode")) extra.put("mode",mode);
            p.put("v2rayXhttpExtra",extra.toString()); output.put("profile",p.getString("name")); break;
        }
        require(selected!=null,"Matching XHTTP profile missing");
        profiles.edit().putString("profiles_json",all.toString()).putString("selected_profile_id",selected).commit();
        Set<String> excluded=new HashSet<>(); JSONArray old=saved.getJSONArray("excluded");
        for (int i=0;i<old.length();i++) if (!"com.vpnapp".equals(old.getString(i)) && !"com.vpnapp.test".equals(old.getString(i))) excluded.add(old.getString(i));
        settings.edit().putInt("mtu_size",mtu).putBoolean("bypass_iran",false)
            .putBoolean("bypass_private_ips",true).putStringSet("excluded_apps",excluded).commit();
        output.put("mode",args.getString("expected_mode",mode)).put("requested_mtu",mtu);
    }
    private void restore(JSONObject output) throws Exception {
        JSONObject saved=readBackup(); Set<String> excluded=new HashSet<>(); JSONArray old=saved.getJSONArray("excluded");
        for (int i=0;i<old.length();i++) excluded.add(old.getString(i));
        profiles.edit().putString("profiles_json",saved.getString("profiles"))
            .putString("selected_profile_id",saved.getString("selected")).commit();
        settings.edit().putInt("mtu_size",saved.getInt("mtu")).putBoolean("bypass_iran",saved.getBoolean("bypass_iran"))
            .putInt("buffer_size",saved.optInt("buffer_size",2097152)).putBoolean("tcp_no_delay",saved.optBoolean("tcp_no_delay",true))
            .putBoolean("bypass_private_ips",saved.getBoolean("bypass_private_ips")).putStringSet("excluded_apps",excluded).commit();
        output.put("restored",true);
    }
    private JSONObject request(String label,String address,boolean upload) throws Exception {
        require(vpnActive(),"Traffic test is outside the Android VPN");
        HttpsURLConnection conn=(HttpsURLConnection)new URL(address).openConnection();
        conn.setConnectTimeout(12000); conn.setReadTimeout(12000); conn.setUseCaches(false);
        conn.setRequestProperty("Connection","close"); conn.setRequestProperty("User-Agent","Mozilla/5.0");
        long start=SystemClock.elapsedRealtime();
        try {
            if (upload) {
                conn.setRequestMethod("POST"); conn.setDoOutput(true); conn.setFixedLengthStreamingMode(512000);
                conn.setRequestProperty("Content-Type","application/octet-stream");
                byte[] bytes=new byte[16000]; new Random(1).nextBytes(bytes);
                try(OutputStream stream=conn.getOutputStream()) { for (int i=0;i<32;i++) stream.write(bytes); }
            }
            int code=conn.getResponseCode(); long first=SystemClock.elapsedRealtime()-start;
            ByteArrayOutputStream response=new ByteArrayOutputStream();
            InputStream source=code<400?conn.getInputStream():conn.getErrorStream();
            if (source!=null) try(InputStream stream=source) { byte[] bytes=new byte[16384]; int n; while((n=stream.read(bytes))!=-1) response.write(bytes,0,n); }
            long elapsed=SystemClock.elapsedRealtime()-start;
            JSONObject result=new JSONObject().put("label",label).put("code",code).put("first_byte_ms",first)
                .put("total_ms",elapsed).put("bytes",response.size());
            if(upload) result.put("upload_mbps",4096.0/Math.max(1,elapsed));
            if("geo".equals(label) && code==200) {
                JSONObject geo=new JSONObject(response.toString("UTF-8"));
                result.put("ip",geo.optString("ip")).put("country",geo.optString("country_code",geo.optString("country")));
            }
            require(code>=200&&code<500,"Tunnel HTTP request failed: "+label+" code="+code);
            return result;
        } finally { conn.disconnect(); }
    }
    private void diagnose(JSONObject output) throws Exception {
        prepare(output); launch(); require(!anyVpn(),"VPN is active before test");
        int number=Integer.parseInt(args.getString("rounds","3")); int mtu=output.getInt("requested_mtu");
        for(int round=1;round<=number;round++) {
            JSONObject result=new JSONObject().put("round",round); rounds.put(result);
            long start=SystemClock.elapsedRealtime(); click("Connect");
            long end=SystemClock.elapsedRealtime()+75000; long ping=-1; String ip=""; boolean applied=false;
            while(SystemClock.elapsedRealtime()<end) {
                for(String value:values("Ping")) if(value.matches("\\d+ms")) ping=Long.parseLong(value.substring(0,value.length()-2));
                for(String value:values("Public IP")) if(value.matches("\\d{1,3}(\\.\\d{1,3}){3}")) ip=value;
                for(String value:values("MTU")) if(value.equals(String.valueOf(mtu))) applied=true;
                if(ping>0&&!ip.isEmpty()&&vpnActive()) break;
                Thread.sleep(150);
            }
            result.put("ui_ready_ms",SystemClock.elapsedRealtime()-start).put("ui_ping_ms",ping)
                .put("ui_public_ip",ip).put("mtu_display_matches",applied).put("vpn_active",vpnActive());
            if(ping<=0 || ip.isEmpty()) {
                result.put("connected_label",find("Connected")!=null).put("ping_label",find("Ping")!=null)
                    .put("public_label",find("Public IP")!=null).put("mtu_label",find("MTU")!=null);
                try { if(vpnActive()) result.put("failure_direct_geo",request("geo","https://api.ip.sb/geoip",false)); }
                catch(Throwable problem) { result.put("failure_direct_geo_error",problem.toString()); }
                android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot();
                if(screenshot!=null) try(OutputStream stream=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),
                    "diagnostic-failure-"+args.getString("case","last")+".png"))) {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream);
                }
            }
            require(ping>0&&!ip.isEmpty(),"UI Public IP/ping unavailable after reconnect");
            require(vpnActive(),"Android VPN not active"); require(applied,"Displayed MTU differs from requested setting");
            ConnectivityManager connectivity=getTargetContext().getSystemService(ConnectivityManager.class);
            android.net.LinkProperties link=connectivity.getLinkProperties(connectivity.getActiveNetwork());
            require(link!=null,"VPN link properties are missing");
            result.put("link_mtu",link.getMtu()).put("tun_interface",link.getInterfaceName());
            require(link.getMtu()==mtu,"Actual VPN MTU differs from requested setting");
            try(Reader reader=new InputStreamReader(new FileInputStream(new File(getTargetContext().getCacheDir(),"sing-box-config.json")),"UTF-8")) {
                StringBuilder text=new StringBuilder(); char[] buffer=new char[4096]; int n;
                while((n=reader.read(buffer))!=-1) text.append(buffer,0,n);
                JSONArray outbounds=new JSONObject(text.toString()).getJSONArray("outbounds");
                for(int i=0;i<outbounds.length();i++) {
                    JSONObject outbound=outbounds.getJSONObject(i);
                    if(!"proxy".equals(outbound.optString("tag"))) continue;
                    String generated=outbound.getJSONObject("transport").getString("mode");
                    result.put("generated_mode",generated); require(output.getString("mode").equals(generated),"Core mode differs from selected test mode");
                }
            }
            JSONObject geo;
            try {
                geo=request("geo","https://api.ip.sb/geoip",false);
                require(!geo.optString("ip").isEmpty(),"Primary GeoIP response has no IP");
            } catch(Throwable error) { geo=request("geo","https://ipinfo.io/json",false); }
            require(ip.equals(geo.optString("ip")),"UI Public IP differs from IP reached through TUN"); result.put("geo",geo);
            result.put("instagram",request("instagram","https://www.instagram.com/robots.txt",false));
            result.put("instagram_api",request("instagram-api","https://i.instagram.com/api/v1/si/fetch_headers/?challenge_type=signup",false));
            result.put("download",request("download","https://speed.cloudflare.com/__down?bytes=512000",false));
            result.put("upload",request("upload","https://speed.cloudflare.com/__up",true));
            long stopping=SystemClock.elapsedRealtime();
            if(round%2==0) {
                boolean sent=false;
                android.app.NotificationManager manager=getTargetContext().getSystemService(android.app.NotificationManager.class);
                for(android.service.notification.StatusBarNotification notice:manager.getActiveNotifications()) {
                    android.app.Notification.Action[] actions=notice.getNotification().actions;
                    if(actions==null) continue;
                    for(android.app.Notification.Action action:actions) if("Disconnect".contentEquals(action.title)) {
                        action.actionIntent.send(); sent=true; break;
                    }
                    if(sent) break;
                }
                require(sent,"Notification Disconnect action missing"); result.put("disconnect_source","notification_action");
            } else { click("Disconnect"); result.put("disconnect_source","app"); }
            end=SystemClock.elapsedRealtime()+12000;
            while((anyVpn() || !shell("pidof com.vpnapp:vpn").isEmpty() || find("Not Connected")==null) && SystemClock.elapsedRealtime()<end) Thread.sleep(100);
            result.put("disconnect_ms",SystemClock.elapsedRealtime()-stopping).put("vpn_after_disconnect",anyVpn())
                .put("worker_after_disconnect",shell("pidof com.vpnapp:vpn")).put("idle_ui",find("Not Connected")!=null);
            require(!anyVpn(),"Android VPN remains after disconnect");
            require(result.getString("worker_after_disconnect").isEmpty(),"VPN worker remains after disconnect");
            require(find("Not Connected")!=null,"UI stuck after disconnect");
            Bundle progress=new Bundle(); progress.putString("round",result.toString()); sendStatus(2,progress);
        }
    }
    private void diagnoseOpenConnect(JSONObject output) throws Exception {
        JSONObject saved=readBackup(); JSONArray all=new JSONArray(saved.getString("profiles"));
        String id=UUID.randomUUID().toString(); int mtu=Integer.parseInt(args.getString("mtu","1400"));
        String protocol=args.getString("protocol","OPENCONNECT");
        JSONObject profile=new JSONObject().put("id",id).put("name",protocol+" Diagnostics")
            .put("serverAddress",args.getString("server")).put("protocol",protocol).put("port",443)
            .put("username",args.getString("username")).put("password",args.getString("password"))
            .put("useNative",Boolean.parseBoolean(args.getString("native","true")))
            .put("skipCertificateVerification",Boolean.parseBoolean(args.getString("skip_verify","true")))
            .put("disableDtls",Boolean.parseBoolean(args.getString("disable_dtls","false"))).put("mtu",mtu);
        all.put(profile); profiles.edit().putString("profiles_json",all.toString()).putString("selected_profile_id",id).commit();
        Set<String> excluded=new HashSet<>(settings.getStringSet("excluded_apps",Collections.emptySet()));
        excluded.remove("com.vpnapp"); excluded.remove("com.vpnapp.test");
        settings.edit().putInt("mtu_size",mtu).putBoolean("bypass_iran",false)
            .putBoolean("bypass_private_ips",Boolean.parseBoolean(args.getString("bypass_lan","false")))
            .putInt("buffer_size",Integer.parseInt(args.getString("buffer","262144")))
            .putBoolean("tcp_no_delay",Boolean.parseBoolean(args.getString("no_delay","true")))
            .putStringSet("excluded_apps",excluded).commit();
        launch(); int number=Integer.parseInt(args.getString("rounds","1"));
        boolean expect=Boolean.parseBoolean(args.getString("expect_probes","true"));
        for(int round=1;round<=number;round++) {
            JSONObject result=new JSONObject().put("round",round); rounds.put(result);
            click("Connect"); waitFor("Connected",65000);
            long end=SystemClock.elapsedRealtime()+20000; long ping=-1; String ip="";
            do {
                for(String value:values("Ping")) if(value.matches("\\d+ms")) ping=Long.parseLong(value.substring(0,value.length()-2));
                for(String value:values("Public IP")) if(value.matches("\\d{1,3}(\\.\\d{1,3}){3}")) ip=value;
                if(ping>0&&!ip.isEmpty()&&vpnActive()) break;
                Thread.sleep(150);
            } while(SystemClock.elapsedRealtime()<end);
            result.put("ui_ping_ms",ping).put("ui_public_ip",ip).put("active_vpn_for_app",vpnActive())
                .put("ui_mtu",new JSONArray(values("MTU"))).put("transport",new JSONArray(values("Transport")));
            if(vpnActive()) {
                ConnectivityManager manager=getTargetContext().getSystemService(ConnectivityManager.class);
                android.net.LinkProperties link=manager.getLinkProperties(manager.getActiveNetwork());
                result.put("actual_mtu",link.getMtu());
                result.put("actual_ui_mtu_matches",values("MTU").contains(String.valueOf(link.getMtu())));
                JSONObject geo=request("geo","https://api.ip.sb/geoip",false); result.put("geo",geo);
                result.put("download",request("download","https://speed.cloudflare.com/__down?bytes=512000",false));
                result.put("upload",request("upload","https://speed.cloudflare.com/__up",true));
                result.put("public_ip_matches",ip.equals(geo.optString("ip")));
            }
            long stopping=SystemClock.elapsedRealtime();
            if(round%2==0) {
                boolean sent=false;
                android.app.NotificationManager manager=getTargetContext().getSystemService(android.app.NotificationManager.class);
                for(android.service.notification.StatusBarNotification notice:manager.getActiveNotifications()) {
                    android.app.Notification.Action[] actions=notice.getNotification().actions; if(actions==null) continue;
                    for(android.app.Notification.Action action:actions) if("Disconnect".contentEquals(action.title)) {
                        action.actionIntent.send(); sent=true; break;
                    }
                    if(sent) break;
                }
                require(sent,"Notification Disconnect action missing");
            } else click("Disconnect");
            end=SystemClock.elapsedRealtime()+12000;
            while((anyVpn() || !shell("pidof com.vpnapp:vpn").isEmpty() || find("Not Connected")==null) && SystemClock.elapsedRealtime()<end) Thread.sleep(100);
            result.put("disconnect_ms",SystemClock.elapsedRealtime()-stopping).put("vpn_after_disconnect",anyVpn())
                .put("worker_after_disconnect",shell("pidof com.vpnapp:vpn"));
            require(!anyVpn() && shell("pidof com.vpnapp:vpn").isEmpty() && find("Not Connected")!=null,"OpenConnect did not fully disconnect");
            Bundle progress=new Bundle(); progress.putString("round",result.toString()); sendStatus(2,progress);
            if(expect) {
                require(ping>0&&!ip.isEmpty()&&result.optBoolean("public_ip_matches"),protocol+" UI probes failed");
                require(result.optInt("actual_mtu")<=mtu && result.optBoolean("actual_ui_mtu_matches"),"Actual/displayed MTU mismatch");
            }
        }
    }

    private AccessibilityNodeInfo scrollable(AccessibilityNodeInfo node) {
        if(node==null) return null;
        if(node.isScrollable()) return node;
        for(int i=0;i<node.getChildCount();i++) { AccessibilityNodeInfo found=scrollable(node.getChild(i)); if(found!=null) return found; }
        return null;
    }
    private void scrollTo(String label) throws Exception {
        for(int attempt=0;attempt<12;attempt++) {
            AccessibilityNodeInfo node=find(label);
            if(node!=null && node.isVisibleToUser()) return;
            AccessibilityNodeInfo view=scrollable(getUiAutomation().getRootInActiveWindow());
            require(view!=null,"Cannot scroll to "+label);
            swipeUp(view);
            Thread.sleep(250);
        }
        throw new AssertionError("Missing scrolled control: "+label);
    }
    private void swipeUp(AccessibilityNodeInfo view) throws Exception {
        Rect r=new Rect(); view.getBoundsInScreen(r);
        shell("input swipe "+r.centerX()+" "+(r.top+r.height()*3/4)+" "+r.centerX()+" "+(r.top+r.height()/4)+" 350");
    }
    private AccessibilityNodeInfo checkable(AccessibilityNodeInfo node) {
        if(node==null) return null;
        if(node.isCheckable()) return node;
        for(int i=0;i<node.getChildCount();i++) { AccessibilityNodeInfo found=checkable(node.getChild(i)); if(found!=null) return found; }
        return null;
    }
    private void toggleForce(boolean desired) throws Exception {
        scrollTo("Force XHTTP stream-up"); AccessibilityNodeInfo node=find("Force XHTTP stream-up"), toggle=null;
        for(int i=0;i<5 && node!=null && toggle==null;i++,node=node.getParent()) toggle=checkable(node);
        require(toggle!=null,"Force switch missing");
        if(toggle.isChecked()!=desired) require(toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK),"Cannot change Force switch");
        Thread.sleep(500); require(settings.getBoolean("force_xhttp_stream_up",true)==desired,"Force preference not saved");
    }
    private void diagnosePolicy(JSONObject output) throws Exception {
        String old=profiles.getString("profiles_json","[]"); boolean had=settings.contains("force_xhttp_stream_up");
        boolean oldForce=settings.getBoolean("force_xhttp_stream_up",true);
        JSONArray data=new JSONArray(old);
        data.put(new JSONObject().put("id","policy-fixture").put("name","XHTTP Policy Fixture")
            .put("protocol","VLESS").put("serverAddress","cdn.example").put("v2rayNetwork","splithttp")
            .put("v2rayType","stream-one").put("v2rayXhttpExtra","{\"mode\":\"stream-one\",\"xPaddingBytes\":\"100-200\"}"));
        profiles.edit().putString("profiles_json",data.toString()).commit(); settings.edit().remove("force_xhttp_stream_up").commit();
        try {
            launch(); JSONArray migrated=new JSONArray(profiles.getString("profiles_json","[]")); int count=0;
            for(int i=0;i<migrated.length();i++) {
                JSONObject p=migrated.getJSONObject(i); String network=p.optString("v2rayNetwork");
                if(!"xhttp".equalsIgnoreCase(network)&&!"splithttp".equalsIgnoreCase(network)) continue;
                count++; require("stream-up".equals(p.optString("v2rayType"))&&p.has("v2rayOriginalXhttpMode"),"Startup migration missed XHTTP");
            }
            output.put("migrated_xhttp",count);
            click("Add Profile"); click("V2ray (VLESS/VMess/Trojan)");
            runOnMainSync(()-> {
                android.content.ClipboardManager clipboard=getTargetContext().getSystemService(android.content.ClipboardManager.class);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("XHTTP test",
                    "vless://00000000-0000-4000-8000-000000000001@cdn.example:443?type=xhttp&mode=packet-up&security=tls#XHTTP-Clipboard-Policy"));
            });
            click("Import from Clipboard"); Thread.sleep(600);
            JSONArray imported=new JSONArray(profiles.getString("profiles_json","[]")); boolean clipboard=false;
            for(int i=0;i<imported.length();i++) { JSONObject p=imported.getJSONObject(i);
                if("XHTTP-Clipboard-Policy".equals(p.optString("name"))) clipboard="stream-up".equals(p.optString("v2rayType"))&&"packet-up".equals(p.optString("v2rayOriginalXhttpMode"));
            }
            require(clipboard,"Clipboard did not force stream-up"); output.put("clipboard_forced",true);
            click("Settings"); waitFor("Always-on VPN & Kill Switch",5000); toggleForce(false);
            JSONArray restored=new JSONArray(profiles.getString("profiles_json","[]")); boolean fixture=false;
            for(int i=0;i<restored.length();i++) { JSONObject p=restored.getJSONObject(i);
                require(!p.has("v2rayOriginalXhttpMode"),"Original mode metadata did not clear");
                if("policy-fixture".equals(p.optString("id"))) fixture="stream-one".equals(p.optString("v2rayType"))&&
                    "{\"mode\":\"stream-one\",\"xPaddingBytes\":\"100-200\"}".equals(p.optString("v2rayXhttpExtra"));
            }
            require(fixture,"Turning force off did not restore original mode/extra"); output.put("original_restored",true);
            toggleForce(true); output.put("reenabled",true);
        } finally {
            profiles.edit().putString("profiles_json",old).commit();
            SharedPreferences.Editor edit=settings.edit(); if(had) edit.putBoolean("force_xhttp_stream_up",oldForce); else edit.remove("force_xhttp_stream_up"); edit.commit();
        }
    }
    private AccessibilityNodeInfo passwordField(AccessibilityNodeInfo node) {
        if(node==null) return null;
        if(node.isEditable()&&node.isPassword()&&node.isVisibleToUser()) return node;
        for(int i=0;i<node.getChildCount();i++) { AccessibilityNodeInfo field=passwordField(node.getChild(i)); if(field!=null) return field; }
        return null;
    }
    private void diagnoseKeyboard(JSONObject output) throws Exception {
        android.accessibilityservice.AccessibilityServiceInfo service=getUiAutomation().getServiceInfo();
        service.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        getUiAutomation().setServiceInfo(service);
        launch(); JSONArray results=new JSONArray();
        for(String protocol:new String[]{"OpenConnect","SSTP"}) {
            click("Add Profile"); click(protocol); waitFor("Protocol",5000);
            for(int i=0;i<12&&passwordField(getUiAutomation().getRootInActiveWindow())==null;i++) {
                AccessibilityNodeInfo view=scrollable(getUiAutomation().getRootInActiveWindow());
                require(view!=null,"Cannot reveal password input"); swipeUp(view); Thread.sleep(250);
            }
            AccessibilityNodeInfo field=passwordField(getUiAutomation().getRootInActiveWindow());
            require(field!=null&&field.performAction(AccessibilityNodeInfo.ACTION_CLICK),"Password cannot receive focus");
            Thread.sleep(1200); Rect keyboard=new Rect(), password=new Rect();
            for(android.view.accessibility.AccessibilityWindowInfo window:getUiAutomation().getWindows()) {
                if(window.getType()==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) window.getBoundsInScreen(keyboard);
                if(window.getType()==android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                    AccessibilityNodeInfo node=passwordField(window.getRoot());
                    if(node!=null) node.getBoundsInScreen(password);
                }
            }
            require(keyboard.height()>0&&password.height()>0,"Keyboard or password bounds unavailable");
            require(password.bottom<=keyboard.top,"Keyboard covers the password field");
            results.put(new JSONObject().put("protocol",protocol).put("password_bottom",password.bottom).put("keyboard_top",keyboard.top));
            shell("input keyevent 4"); Thread.sleep(250); click("Back"); waitFor("Not Connected",5000);
        }
        output.put("keyboard_checks",results);
    }

    private void collectText(AccessibilityNodeInfo node,StringBuilder result) {
        if(node==null) return;
        if(node.getText()!=null) result.append(node.getText()).append('\n');
        if(node.getContentDescription()!=null) result.append(node.getContentDescription()).append('\n');
        for(int i=0;i<node.getChildCount();i++) collectText(node.getChild(i),result);
    }
    private void collectKeyboardNodes(AccessibilityNodeInfo node,StringBuilder result) {
        if(node==null) return;
        result.append(node.getClassName()).append(" editable=").append(node.isEditable()).append(" password=").append(node.isPassword())
            .append(" text=").append(node.getText()).append(" hint=").append(node.getHintText()).append(" desc=").append(node.getContentDescription()).append('\n');
        for(int i=0;i<node.getChildCount();i++) collectKeyboardNodes(node.getChild(i),result);
    }
    private void screenshot(String name) throws Exception {
        android.graphics.Bitmap bitmap=getUiAutomation().takeScreenshot();
        if(bitmap!=null) try(OutputStream stream=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),name))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream);
        }
    }
    private AccessibilityNodeInfo videoCard(AccessibilityNodeInfo node) {
        if(node==null) return null;
        String desc=String.valueOf(node.getContentDescription());
        if(desc.contains("Me at the zoo")&&desc.toLowerCase(Locale.ROOT).contains("jawed")) return node;
        if(!node.isEditable()&&"Me at the zoo".equals(String.valueOf(node.getText()))) return node;
        for(int i=0;i<node.getChildCount();i++) { AccessibilityNodeInfo found=videoCard(node.getChild(i)); if(found!=null) return found; }
        return null;
    }
    private void diagnoseYoutube(JSONObject output) throws Exception {
        prepare(output); launch(); click("Connect"); waitFor("Connected",45000); Thread.sleep(2500);
        output.put("youtube_web",request("youtube-web","https://www.youtube.com/",false));
        Intent video=new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://www.youtube.com/watch?v=jNQXAC9IVRw"))
            .setPackage("com.google.android.youtube").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        shell("am force-stop com.google.android.youtube");
        getTargetContext().startActivity(video); Thread.sleep(2000);
        if(find("Don’t allow")!=null) click("Don’t allow");
        else if(find("Don't allow")!=null) click("Don't allow");
        Thread.sleep(10000);
        StringBuilder initial=new StringBuilder(); collectText(getUiAutomation().getRootInActiveWindow(),initial);
        if(initial.toString().contains("Before you continue")) {
            for(int i=0;i<3;i++) { shell("input swipe 540 1800 540 500 350"); Thread.sleep(400); }
            AccessibilityNodeInfo reject=find("Reject all");
            output.put("consent_reject_found",reject!=null);
            require(reject!=null,"Consent rejection button unavailable");
            Rect button=new Rect(); reject.getBoundsInScreen(button);
            shell("input tap "+button.centerX()+" "+button.centerY()); Thread.sleep(15000);
        }
        getTargetContext().startActivity(video); Thread.sleep(20000);
        if(find("Search")!=null) {
            click("Search"); Thread.sleep(600); setField(0,"Me at the zoo"); shell("input keyevent 66");
            long searchStart=SystemClock.elapsedRealtime(); AccessibilityNodeInfo card=null;
            while(card==null&&SystemClock.elapsedRealtime()-searchStart<45000) { Thread.sleep(500); card=videoCard(getUiAutomation().getRootInActiveWindow()); }
            output.put("search_result_ms",SystemClock.elapsedRealtime()-searchStart).put("video_card_found",card!=null);
            if(card!=null) { Rect bounds=new Rect(); card.getBoundsInScreen(bounds); shell("input tap "+bounds.centerX()+" "+bounds.centerY()); Thread.sleep(20000); }
        }
        StringBuilder text=new StringBuilder(); AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow(); collectText(root,text);
        String caseName=args.getString("case","youtube");
        require(root!=null&&"com.google.android.youtube".contentEquals(root.getPackageName()),"YouTube was not the visible app");
        try(Writer writer=new OutputStreamWriter(new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),
            caseName+"-youtube-ui.private.txt")),"UTF-8")) { writer.write(text.toString()); }
        output.put("youtube_package",root==null?"null":String.valueOf(root.getPackageName()))
            .put("youtube_offline",text.toString().contains("You're offline")||text.toString().contains("Connect to the internet"))
            .put("youtube_known_video",text.toString().contains("Me at the zoo"));
        screenshot(caseName+"-youtube-1.png"); Thread.sleep(7000); screenshot(caseName+"-youtube-2.png");
        getTargetContext().startActivity(new Intent().setClassName("com.vpnapp","com.vpnapp.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitFor("Disconnect",7000); click("Disconnect"); waitFor("Not Connected",12000);
        long end=SystemClock.elapsedRealtime()+7000;
        while((anyVpn()||!shell("pidof com.vpnapp:vpn").isEmpty())&&SystemClock.elapsedRealtime()<end) Thread.sleep(100);
        require(!anyVpn()&&shell("pidof com.vpnapp:vpn").isEmpty(),"VPN remained after YouTube test");
    }

    private void snapshotPhone(JSONObject output) throws Exception {
        require(!anyVpn(),"Disconnect the existing VPN before phone diagnostics");
        saveBackup("");
        JSONObject snapshot=new JSONObject().put("profiles",new JSONObject(profiles.getAll())).put("settings",new JSONObject(settings.getAll()));
        try(Writer writer=new OutputStreamWriter(new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"phone-before-diagnostics.private.json")),"UTF-8")) { writer.write(snapshot.toString()); }
        JSONArray inventory=new JSONArray(), all=new JSONArray(profiles.getString("profiles_json","[]"));
        for(int i=0;i<all.length();i++) {
            JSONObject p=all.getJSONObject(i);
            inventory.put(new JSONObject().put("id",p.optString("id")).put("name",p.optString("name")).put("protocol",p.optString("protocol"))
                .put("server",p.optString("serverAddress")).put("port",p.optInt("port",443)).put("network",p.optString("v2rayNetwork"))
                .put("mode",p.optString("v2rayType")).put("native",p.optBoolean("useNative",true)).put("disable_dtls",p.optBoolean("disableDtls"))
                .put("skip_verify",p.optBoolean("skipCertificateVerification",true)));
        }
        output.put("profiles",inventory).put("mtu",settings.getInt("mtu_size",1400)).put("backup_saved",true);
    }
    private void diagnoseStoredProfile(JSONObject output) throws Exception {
        require(!anyVpn(),"A VPN is already active");
        String old=profiles.getString("selected_profile_id",""), id=args.getString("profile_id","");
        String savedProfiles=profiles.getString("profiles_json","[]"); boolean savedForce=settings.getBoolean("force_xhttp_stream_up",true);
        JSONArray all=new JSONArray(savedProfiles); JSONObject selected=null;
        for(int i=0;i<all.length();i++) if(id.equals(all.getJSONObject(i).optString("id"))) selected=all.getJSONObject(i);
        require(selected!=null,"Stored profile ID missing");
        output.put("profile",selected.optString("name")).put("protocol",selected.optString("protocol"));
        try {
            if(args.containsKey("smart_original")) {
                selected.put("v2rayXhttpModeOverride","smart").put("v2rayOriginalXhttpMode",args.getString("smart_original"));
                selected.remove("v2raySmartXhttpMode");
                profiles.edit().putString("profiles_json",all.toString()).commit();
            }
            if(args.containsKey("mode_override")) {
                selected.put("v2rayType",args.getString("mode_override")); selected.put("v2rayXhttpModeOverride",args.getString("mode_override")); selected.remove("v2rayOriginalXhttpMode");
                settings.edit().putBoolean("force_xhttp_stream_up",false).commit(); output.put("mode_override",args.getString("mode_override"));
            }
            if(args.containsKey("fp_override")) { String fp=args.getString("fp_override"); selected.put("v2rayFp","default".equals(fp)?"":fp); output.put("fp_override",fp); }
            if(args.containsKey("mode_override")||args.containsKey("fp_override")) profiles.edit().putString("profiles_json",all.toString()).commit();
            profiles.edit().putString("selected_profile_id",id).commit(); launch(); click("Connect");
            long start=SystemClock.elapsedRealtime();
            if(args.getString("cancel_smart","false").equals("true")) {
                waitFor("Cancel Smart check",8000); require(!anyVpn(),"Smart check started VPN before traffic verification");
                click("Cancel Smart check"); waitFor("Not Connected",5000); Thread.sleep(10000);
                require(!anyVpn()&&find("Not Connected")!=null,"Canceled Smart check started a late VPN");
                output.put("cancel_verified",true); return;
            }
            while(find("Connected")==null&&SystemClock.elapsedRealtime()-start<65000) {
                if(args.containsKey("smart_original")&&anyVpn()) {
                    JSONArray ready=new JSONArray(profiles.getString("profiles_json","[]")); boolean verified=false;
                    for(int i=0;i<ready.length();i++) if(id.equals(ready.getJSONObject(i).optString("id")))
                        verified=!ready.getJSONObject(i).optString("v2raySmartXhttpMode").isEmpty();
                    require(verified,"VPN started before Smart traffic check was verified and saved");
                }
                Thread.sleep(250);
                if(SystemClock.elapsedRealtime()-start>15000&&find("Not Connected")!=null&&!vpnActive()) break;
            }
            long connectMs=SystemClock.elapsedRealtime()-start;
            Thread.sleep(3000);
            boolean connected=find("Connected")!=null;
            output.put("connected",connected).put("connect_ms",connectMs).put("vpn_for_app",vpnActive());
            if(connected) {
                long warmEnd=SystemClock.elapsedRealtime()+15000;
                while((values("Ping").toString().contains("Checking")||values("Public IP").toString().contains("Checking"))&&SystemClock.elapsedRealtime()<warmEnd) Thread.sleep(250);
            }
            StringBuilder ui=new StringBuilder(); collectText(getUiAutomation().getRootInActiveWindow(),ui);
            try(Writer writer=new OutputStreamWriter(new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),args.getString("case")+"-ui.private.txt")),"UTF-8")) { writer.write(ui.toString()); }
            screenshot(args.getString("case")+"-phone.png");
            if(connected&&vpnActive()) {
                if("VLESS".equals(selected.optString("protocol"))) {
                    try(Reader reader=new InputStreamReader(new FileInputStream(new File(getTargetContext().getCacheDir(),"sing-box-config.json")),"UTF-8")) {
                        StringBuilder config=new StringBuilder(); char[] buffer=new char[4096]; int count;
                        while((count=reader.read(buffer))!=-1) config.append(buffer,0,count);
                        JSONArray outbounds=new JSONObject(config.toString()).getJSONArray("outbounds");
                        for(int i=0;i<outbounds.length();i++) if("proxy".equals(outbounds.getJSONObject(i).optString("tag"))) {
                            JSONObject proxy=outbounds.getJSONObject(i);
                            if("xhttp".equals(selected.optString("v2rayNetwork"))) {
                                String generatedMode=proxy.getJSONObject("transport").optString("mode");
                                output.put("generated_mode",generatedMode);
                                if(args.containsKey("mode_override")) require(args.getString("mode_override").equals(generatedMode),"Generated mode differs from test mode");
                            }
                            if(!"none".equals(selected.optString("vlessEncryption","none"))) {
                                boolean preserved=selected.optString("vlessEncryption").equals(proxy.optString("encryption"));
                                output.put("generated_encryption_preserved",preserved); require(preserved,"Encryption lost between UI and VPN service");
                            }
                        }
                    }
                }
                output.put("ping",values("Ping")).put("public_ip",values("Public IP")).put("mtu_display",values("MTU"));
                if("true".equals(args.getString("instagram_probe"))) {
                    JSONArray instagram = new JSONArray();
                    for(String url : new String[]{"https://www.instagram.com/robots.txt", "https://i.instagram.com/api/v1/", "https://graph.instagram.com/"}) {
                        try { instagram.put(request("instagram-connectivity",url,false)); }
                        catch(Exception e) { instagram.put(new JSONObject().put("error",e.getClass().getSimpleName())); }
                    }
                    output.put("instagram_connectivity",instagram);
                }
                try { output.put("geo",request("geo","https://ipwho.is/",false)); } catch(Exception e) { output.put("geo_error",e.getClass().getSimpleName()+": "+e.getMessage()); }
                try { output.put("download",request("download","https://speed.cloudflare.com/__down?bytes=64000",false)); } catch(Exception e) { output.put("traffic_error",e.getClass().getSimpleName()+": "+e.getMessage()); }
                if(args.containsKey("smart_original")) {
                    JSONArray learned=new JSONArray(profiles.getString("profiles_json","[]"));
                    for(int i=0;i<learned.length();i++) if(id.equals(learned.getJSONObject(i).optString("id")))
                        output.put("learned_mode",learned.getJSONObject(i).optString("v2raySmartXhttpMode"));
                    require(output.optString("generated_mode").equals(output.optString("learned_mode")),"Smart working mode not saved");
                }
            }
        } finally {
            try {
                boolean sent=tryClick("Disconnect");
                if(!sent) { tryClick("Cancel Smart check"); tryClick("Cancel"); }
                long end=SystemClock.elapsedRealtime()+12000;
                while(anyVpn()&&SystemClock.elapsedRealtime()<end) {
                    if(!sent) sent=tryClick("Disconnect");
                    Thread.sleep(200);
                }
                output.put("vpn_after_test",anyVpn());
                require(!anyVpn(),"VPN remained after explicit disconnect");
            } finally {
                profiles.edit().putString("selected_profile_id",old).commit();
                if(args.containsKey("mode_override")||args.containsKey("fp_override")||args.containsKey("smart_original")) {
                    profiles.edit().putString("profiles_json",savedProfiles).commit(); settings.edit().putBoolean("force_xhttp_stream_up",savedForce).commit();
                }
            }
        }
    }
    private void restorePhoneProfileState(JSONObject output) throws Exception {
        require(!anyVpn(),"VPN active during profile restore");
        JSONObject saved;
        try(Reader reader=new InputStreamReader(new FileInputStream(new File(getTargetContext().getExternalFilesDir(null),"phone-before-1.3.65.private.json")),"UTF-8")) {
            StringBuilder json=new StringBuilder(); char[] buffer=new char[4096]; int count;
            while((count=reader.read(buffer))!=-1) json.append(buffer,0,count);
            saved=new JSONObject(json.toString()).getJSONObject("profiles");
        }
        JSONArray old=new JSONArray(saved.getString("profiles_json")), current=new JSONArray(profiles.getString("profiles_json","[]"));
        Set<String> oldIds=new HashSet<>(), currentIds=new HashSet<>();
        for(int i=0;i<old.length();i++) oldIds.add(old.getJSONObject(i).getString("id"));
        for(int i=0;i<current.length();i++) currentIds.add(current.getJSONObject(i).getString("id"));
        require(oldIds.equals(currentIds)&&oldIds.size()==44,"Profile inventory changed; refusing broad restore");
        profiles.edit().putString("profiles_json",saved.getString("profiles_json"))
            .putString("selected_profile_id",saved.optString("selected_profile_id")).commit();
        output.put("restored_profiles",oldIds.size());
    }
    private void fixStoredSstpHost(JSONObject output) throws Exception {
        JSONArray all=new JSONArray(profiles.getString("profiles_json","[]")); boolean changed=false;
        for(int i=0;i<all.length();i++) { JSONObject p=all.getJSONObject(i);
            if("SSTP".equals(p.optString("protocol"))&&"sstp.rahanetmi.com".equalsIgnoreCase(p.optString("serverAddress"))) {
                p.put("serverAddress","sstp.rahanetmci.com"); changed=true;
            }
        }
        require(changed,"Expected SSTP typo not found"); profiles.edit().putString("profiles_json",all.toString()).commit(); output.put("corrected",true);
    }
    private void fixPhoneCompatibility(JSONObject output) throws Exception {
        JSONArray all=new JSONArray(profiles.getString("profiles_json","[]")); int changed=0;
        for(int i=0;i<all.length();i++) { JSONObject p=all.getJSONObject(i);
            if("s1.rahanetmci.com".equals(p.optString("serverAddress"))&&"xhttp".equals(p.optString("v2rayNetwork"))) {
                p.put("v2rayXhttpModeOverride","auto").put("v2rayType","auto"); changed++;
            }
        }
        require(changed==5,"Expected five diagnosed profiles"); profiles.edit().putString("profiles_json",all.toString()).commit(); output.put("custom_auto_profiles",changed);
    }
    private void checkPhoneModeUi(JSONObject output) throws Exception {
        String saved=profiles.getString("profiles_json","[]"), oldId=profiles.getString("selected_profile_id","");
        JSONArray all=new JSONArray(saved); JSONObject target=null;
        for(int i=0;i<all.length();i++) if("s1.rahanetmci.com".equals(all.getJSONObject(i).optString("serverAddress"))) {target=all.getJSONObject(i);break;}
        require(target!=null,"Compatibility profile missing");
        try {
            profiles.edit().putString("profiles_json",new JSONArray().put(target).toString()).putString("selected_profile_id",target.getString("id")).commit();
            launch(); clickProfileEdit(target.getString("name")); scrollTo("auto"); click("auto"); click("Follow app setting"); click("Save"); waitFor("Not Connected",7000);
            JSONObject defaultMode=new JSONArray(profiles.getString("profiles_json","[]")).getJSONObject(0);
            require(!defaultMode.has("v2rayXhttpModeOverride")&&"stream-up".equals(defaultMode.optString("v2rayType")),"Follow app setting did not restore force");
            clickProfileEdit(target.getString("name")); scrollTo("Follow app setting"); click("Follow app setting"); click("auto"); click("Save"); waitFor("Not Connected",7000);
            JSONObject custom=new JSONArray(profiles.getString("profiles_json","[]")).getJSONObject(0);
            require("auto".equals(custom.optString("v2rayXhttpModeOverride"))&&"auto".equals(custom.optString("v2rayType")),"Custom auto was not saved");
            output.put("follow_force_verified",true).put("custom_auto_verified",true);
            clickProfileEdit(target.getString("name")); scrollTo("auto"); click("auto"); click("Smart"); click("Save"); waitFor("Not Connected",7000);
            JSONObject smart=new JSONArray(profiles.getString("profiles_json","[]")).getJSONObject(0);
            require("smart".equals(smart.optString("v2rayXhttpModeOverride")),"Smart selection was not saved");
            clickProfileEdit(target.getString("name")); scrollTo("Smart"); click("Smart"); click("packet-up"); click("Save"); waitFor("Not Connected",7000);
            JSONObject manual=new JSONArray(profiles.getString("profiles_json","[]")).getJSONObject(0);
            require("packet-up".equals(manual.optString("v2rayXhttpModeOverride"))&&"packet-up".equals(manual.optString("v2rayType")),"Manual mode did not override Smart");
            output.put("smart_saved",true).put("manual_after_smart_verified",true);
        } finally { profiles.edit().putString("profiles_json",saved).putString("selected_profile_id",oldId).commit(); }
    }
    private void clickProfileEdit(String name) {
        AccessibilityNodeInfo parent=find(name), edit=null;
        while(parent!=null&&edit==null) { edit=find(parent,"Edit"); parent=parent.getParent(); }
        require(edit!=null,"Profile Edit control missing");
        while(edit!=null&&!edit.isClickable()) edit=edit.getParent();
        require(edit!=null&&edit.performAction(AccessibilityNodeInfo.ACTION_CLICK),"Profile Edit click failed");
    }
    private void checkVlessEncryptionImport(JSONObject output) throws Exception {
        require(!anyVpn(),"VPN already active");
        String saved=profiles.getString("profiles_json","[]"), old=profiles.getString("selected_profile_id","");
        String link;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(new File(getTargetContext().getExternalFilesDir(null),"hamed-encryption-links.private.txt")),"UTF-8"))) { link=reader.readLine(); }
        String encryption=android.net.Uri.parse(link.replace("vless://","http://")).getQueryParameter("encryption");
        require(encryption!=null&&encryption.startsWith("mlkem768x25519plus."),"Expected encrypted VLESS fixture");
        final String importedLink=link.split("#",2)[0]+"#Encrypted-Import-Check";
        profiles.edit().putString("profiles_json","[]").putString("selected_profile_id","").commit();
        try {
            launch(); click("Add Profile"); click("V2ray (VLESS/VMess/Trojan)");
            runOnMainSync(()->getTargetContext().getSystemService(android.content.ClipboardManager.class)
                .setPrimaryClip(android.content.ClipData.newPlainText("Encrypted VLESS test",importedLink)));
            click("Import from Clipboard"); Thread.sleep(1000);
            JSONArray imported=new JSONArray(profiles.getString("profiles_json","[]"));
            require(imported.length()==1&&encryption.equals(imported.getJSONObject(0).optString("vlessEncryption")),"VLESS encryption lost on clipboard import");
            output.put("clipboard_encryption_preserved",true);
            clickProfileEdit("Encrypted-Import-Check"); scrollTo(encryption);
            require(find(encryption)!=null,"Encryption value missing from editor");
            click("Save"); Thread.sleep(700);
            require(encryption.equals(new JSONArray(profiles.getString("profiles_json","[]")).getJSONObject(0).optString("vlessEncryption")),"Editor dropped encryption");
            output.put("editor_encryption_preserved",true);
        } finally { profiles.edit().putString("profiles_json",saved).putString("selected_profile_id",old).commit(); }
    }
    private void repairStoredVlessEncryption(JSONObject output) throws Exception {
        require(!anyVpn(),"VPN already active");
        JSONArray all=new JSONArray(profiles.getString("profiles_json","[]")); int changed=0;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(new File(getTargetContext().getExternalFilesDir(null),"hamed-encryption-links.private.txt")),"UTF-8"))) {
            String link; while((link=reader.readLine())!=null) {
                android.net.Uri uri=android.net.Uri.parse(link.replace("vless://","http://"));
                String encryption=uri.getQueryParameter("encryption");
                require(encryption!=null&&encryption.startsWith("mlkem768x25519plus."),"Expected subscription encryption");
                for(int i=0;i<all.length();i++) { JSONObject p=all.getJSONObject(i);
                    if("VLESS".equals(p.optString("protocol"))&&uri.getHost().equals(p.optString("serverAddress"))&&uri.getUserInfo().equals(p.optString("v2rayUuid"))&&p.optString("name").contains("Mo-Ebrahimi")) {
                        p.put("vlessEncryption",encryption); changed++;
                    }
                }
            }
        }
        require(changed==4,"Expected four exact matching profiles");
        profiles.edit().putString("profiles_json",all.toString()).commit(); output.put("encrypted_profiles_repaired",changed);
    }
    private void pingPhoneProfiles(JSONObject output) throws Exception {
        require(!anyVpn(),"Disconnect VPN before batch latency test");
        String saved=profiles.getString("profiles_json","[]"); JSONArray all=new JSONArray(saved), included=new JSONArray();
        for(int i=0;i<all.length();i++) if(!args.getString("skip_name", "").equals(all.getJSONObject(i).optString("name"))) included.put(all.getJSONObject(i));
        int expected=included.length(); boolean filtered=expected!=all.length();
        if(filtered) profiles.edit().putString("profiles_json",included.toString()).commit();
        try {
        launch(); click("Ping");
        long end=SystemClock.elapsedRealtime()+expected*16000L+30000L; int delivered=0;
        String log=""; java.util.regex.Pattern pattern=java.util.regex.Pattern.compile("(?:Real delay for |OpenConnect SSL delay for |(?:OPENCONNECT|SSTP) TLS delay for |Sing-box delay test failed for |OpenConnect delay test failed for |(?:OPENCONNECT|SSTP) TLS delay test failed for )[^\\r\\n]+");
        while(SystemClock.elapsedRealtime()<end) {
            Thread.sleep(1000); log=shell("logcat -d -v brief -s SingBoxDelayTester:D");
            java.util.regex.Matcher matcher=pattern.matcher(log); JSONArray results=new JSONArray(); while(matcher.find()) results.put(matcher.group());
            if(results.length()>delivered) { Bundle progress=new Bundle(); progress.putString("round",new JSONObject().put("done",results.length()).put("total",expected).put("latest",results.getString(results.length()-1)).toString()); sendStatus(2,progress); delivered=results.length(); }
            if(delivered>=expected) { output.put("latency_results",results); break; }
        }
        output.put("completed",delivered).put("total",expected); require(delivered==expected,"Batch latency did not finish");
        } finally { if(filtered) profiles.edit().putString("profiles_json",saved).commit(); }
    }
    private void probeFailedTransports(JSONObject output) throws Exception {
        require(!anyVpn(),"A VPN is active during direct transport diagnosis");
        JSONArray all=new JSONArray(profiles.getString("profiles_json","[]")), results=new JSONArray();
        for(int i=0;i<all.length();i++) {
            JSONObject p=all.getJSONObject(i); String host=p.optString("serverAddress"), name=p.optString("name");
            if(!name.contains("Mo-Ebrahimi")&&!"s1.rahanetmci.com".equals(host)&&!"localhost".equals(host)) continue;
            JSONObject result=new JSONObject().put("id",p.optString("id")).put("name",name);
            try(java.net.Socket raw=new java.net.Socket()) {
                raw.connect(new java.net.InetSocketAddress(host,p.optInt("port",443)),4000); raw.setSoTimeout(4000); result.put("tcp",true);
                if("tls".equals(p.optString("v2rayTls"))) {
                    String sni=p.optString("v2raySni",host); if(sni.isEmpty()) sni=host;
                    try(javax.net.ssl.SSLSocket tls=(javax.net.ssl.SSLSocket)((javax.net.ssl.SSLSocketFactory)javax.net.ssl.SSLSocketFactory.getDefault()).createSocket(raw,sni,p.optInt("port",443),true)) {
                        tls.setSoTimeout(4000); javax.net.ssl.SSLParameters params=tls.getSSLParameters(); params.setEndpointIdentificationAlgorithm("HTTPS"); params.setApplicationProtocols(new String[]{"http/1.1"}); tls.setSSLParameters(params);
                        tls.startHandshake(); result.put("tls",true);
                        String network=p.optString("v2rayNetwork"), path=p.optString("v2rayPath","/"); if(path.isEmpty()) path="/";
                        String httpHost=p.optString("v2rayHost",sni); if(httpHost.isEmpty()) httpHost=sni;
                        String request="GET "+("ws".equals(network)?path:"/")+" HTTP/1.1\r\nHost: "+httpHost+"\r\n";
                        if("ws".equals(network)) request+="Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n";
                        else request+="Connection: close\r\n";
                        tls.getOutputStream().write((request+"\r\n").getBytes("UTF-8")); tls.getOutputStream().flush();
                        BufferedReader headers=new BufferedReader(new InputStreamReader(tls.getInputStream(),"UTF-8"));
                        String status=headers.readLine(); result.put("http",status==null?"closed":status);
                    }
                }
            } catch(Exception e) { result.put("error",e.getClass().getSimpleName()+": "+e.getMessage()); }
            results.put(result); Bundle progress=new Bundle(); progress.putString("round",result.toString()); sendStatus(2,progress);
        }
        output.put("transport_results",results);
    }
    private boolean scrollForward(AccessibilityNodeInfo node) {
        if(node==null) return false;
        if(node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for(int i=0;i<node.getChildCount();i++) if(scrollForward(node.getChild(i))) return true;
        return false;
    }
    private void diagnoseQrRelease(JSONObject output) throws Exception {
        String saved=profiles.getString("profiles_json","[]");
        String selected=profiles.getString("selected_profile_id",null);
        try {
            require(!anyVpn(),"QR test requires disconnected VPN");
            launch(); click("Add"); click("Scan QR"); waitFor("Import configuration",20000);
            require(saved.equals(profiles.getString("profiles_json","[]")),"Scan auto-imported without confirmation");
            require(find("QR-Camera-Test")!=null,"Unexpected scanned link"); click("Import");
            for(int i=0;i<8 && find("Share QR-Camera-Test")==null;i++) {
                scrollForward(getUiAutomation().getRootInActiveWindow());Thread.sleep(200);
            }
            click("Share QR-Camera-Test");waitFor("Share QR image",5000);click("Copy link");
            android.content.ClipboardManager clipboard=getTargetContext().getSystemService(android.content.ClipboardManager.class);
            require(clipboard.getPrimaryClip()!=null && clipboard.getPrimaryClip().getItemAt(0).getText().toString().contains("#QR-Camera-Test"),"Release copy link failed");
            screenshot("qr-release-share.png");click("Share QR image");Thread.sleep(500);
            require(getUiAutomation().getRootInActiveWindow()!=null,"Share chooser missing");
            shell("input keyevent 4");output.put("scan_preview_copy_image_share",true);
        } finally {
            profiles.edit().putString("profiles_json",saved).putString("selected_profile_id",selected).commit();
        }
    }
    @Override public void onStart() {
        Bundle bundle=new Bundle(); JSONObject output=new JSONObject();
        profiles=getTargetContext().getSharedPreferences("vpn_profiles",Context.MODE_PRIVATE);
        settings=getTargetContext().getSharedPreferences("vpn_app_prefs",Context.MODE_PRIVATE);
        try {
            String action=args.getString("action","diagnose"); output.put("action",action);
            if("qr_smoke".equals(action)) diagnoseQrRelease(output);
            else if("restore_profile_state".equals(action)) restorePhoneProfileState(output);
            else if("vless_encryption_import".equals(action)) checkVlessEncryptionImport(output);
            else if("phone_repair_encryption".equals(action)) repairStoredVlessEncryption(output);
            else if("reference_ui".equals(action)) {
                if(args.containsKey("reference_click")) click(args.getString("reference_click"));
                Thread.sleep(500);
                StringBuilder ui=new StringBuilder(); collectText(getUiAutomation().getRootInActiveWindow(),ui);
                try(Writer writer=new OutputStreamWriter(new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),args.getString("case")+"-reference-ui.private.txt")),"UTF-8")) { writer.write(ui.toString()); }
                screenshot(args.getString("case")+"-reference.png");
                if("true".equals(args.getString("read_clipboard"))) {
                    launch(); Thread.sleep(500);
                    android.content.ClipboardManager clipboard=getTargetContext().getSystemService(android.content.ClipboardManager.class);
                    android.content.ClipData clip=clipboard.getPrimaryClip();
                    require(clip!=null&&clip.getItemCount()>0,"Reference clipboard empty");
                    String link=clip.getItemAt(0).coerceToText(getTargetContext()).toString();
                    try(Writer writer=new OutputStreamWriter(new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),args.getString("case")+"-reference-link.private.txt")),"UTF-8")) { writer.write(link); }
                    output.put("clipboard_saved_privately",true).put("vless_link",link.startsWith("vless://"));
                }
                output.put("reference_ui_saved",true);
            }
            else if("phone_snapshot".equals(action)) snapshotPhone(output);
            else if("phone_finish".equals(action)) {
                require(!anyVpn(),"VPN is still active");
                JSONObject snapshot=new JSONObject().put("profiles",new JSONObject(profiles.getAll())).put("settings",new JSONObject(settings.getAll()));
                try(Writer writer=new OutputStreamWriter(new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"phone-after-diagnostics.private.json")),"UTF-8")) { writer.write(snapshot.toString()); }
                output.put("vpn_active",false).put("profile_count",new JSONArray(profiles.getString("profiles_json","[]")).length());
            }
            else if("phone_fix_sstp".equals(action)) fixStoredSstpHost(output);
            else if("phone_fix_compatibility".equals(action)) fixPhoneCompatibility(output);
            else if("phone_mode_ui".equals(action)) checkPhoneModeUi(output);
            else if("phone_ping".equals(action)) pingPhoneProfiles(output);
            else if("phone_transport".equals(action)) probeFailedTransports(output);
            else if("stored_profile".equals(action)) diagnoseStoredProfile(output);
            else if("instagram_reconnect".equals(action)) {
                JSONArray checks=new JSONArray();output.put("instagram_rounds",checks);
                for(int round=0;round<3;round++) {
                    JSONObject check=new JSONObject();diagnoseStoredProfile(check);checks.put(check);
                    require(check.optBoolean("connected")&&!check.optBoolean("vpn_after_test"),"Reconnect did not complete");
                    JSONArray responses=check.getJSONArray("instagram_connectivity");
                    for(int i=0;i<responses.length();i++) require(responses.getJSONObject(i).optInt("code")>0,"Instagram request failed after reconnect");
                    if(activity!=null) { runOnMainSync(()->activity.finish());activity=null;Thread.sleep(300); }
                }
                output.put("instagram_rounds",checks);
            }
            else if("import".equals(action)) importSubscription(output);
            else if("restore".equals(action)) restore(output);
            else if("openconnect".equals(action)) diagnoseOpenConnect(output);
            else if("policy".equals(action)) diagnosePolicy(output);
            else if("keyboard".equals(action)) diagnoseKeyboard(output);
            else if("youtube".equals(action)) diagnoseYoutube(output);
            else diagnose(output);
            output.put("success",true);
        } catch(Throwable error) {
            try { output.put("success",false).put("error",error.getClass().getSimpleName()+": "+error.getMessage()); }
            catch(Exception ignored) { }
            try {
                AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
                if("keyboard".equals(args.getString("action"))) {
                    StringBuilder nodes=new StringBuilder(); collectKeyboardNodes(root,nodes);
                    try(Writer writer=new OutputStreamWriter(new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"keyboard-nodes.private.txt")),"UTF-8")) { writer.write(nodes.toString()); }
                }
                output.put("root_package",root==null?"null":String.valueOf(root.getPackageName()))
                    .put("connected_label",find("Connected")!=null).put("disconnecting_label",find("Disconnecting")!=null)
                    .put("idle_label",find("Not Connected")!=null);
                android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot();
                if(screenshot!=null) try(OutputStream stream=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),
                    "diagnostic-failure-"+args.getString("case","last")+".png"))) {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream);
                }
            } catch(Throwable ignored) { }
        } finally {
            try {
                output.put("rounds",rounds); bundle.putString("result",output.toString());
                File file=new File(getTargetContext().getExternalFilesDir(null),"diagnostic-"+args.getString("case","last")+".json");
                try(Writer writer=new OutputStreamWriter(new FileOutputStream(file),"UTF-8")) { writer.write(output.toString()); }
            } catch(Exception ignored) { }
            if(activity!=null) activity.finish();
            finish(output.optBoolean("success")?Activity.RESULT_OK:Activity.RESULT_CANCELED,bundle);
        }
    }
}
