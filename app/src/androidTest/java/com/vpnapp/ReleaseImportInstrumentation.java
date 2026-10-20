package com.vpnapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import java.util.Map;
import java.util.Set;

/** Framework-only UI check: runs against the actual minified, upload-signed release APK. */
public class ReleaseImportInstrumentation extends Instrumentation {
    private Bundle arguments;
    private Activity activity;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String label) {
        if (node == null) return null;
        String description = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
        boolean matchesDescription = java.util.Arrays.stream(description.split("[,\\n]"))
            .anyMatch(part -> label.equals(part.trim()));
        if (label.contentEquals(node.getText() == null ? "" : node.getText()) ||
            matchesDescription) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = find(node.getChild(i), label);
            if (child != null) return child;
        }
        return null;
    }
    private AccessibilityNodeInfo find(String label) {
        AccessibilityNodeInfo node = find(getUiAutomation().getRootInActiveWindow(), label);
        if (node != null) return node;
        for (android.view.accessibility.AccessibilityWindowInfo window : getUiAutomation().getWindows()) {
            node = find(window.getRoot(), label);
            if (node != null) return node;
        }
        return null;
    }
    private void screenshot(String name) throws Exception {
        java.io.File directory = new java.io.File(getTargetContext().getExternalFilesDir(null), "review-screenshots");
        directory.mkdirs();
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(directory, name + ".png"))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally { bitmap.recycle(); }
    }
    private boolean selected(String label) {
        AccessibilityNodeInfo node = find(label);
        while (node != null) {
            if (node.isSelected() || (node.isCheckable() && node.isChecked())) return true;
            node = node.getParent();
        }
        return false;
    }
    private void click(String... labels) {
        long end = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < end) {
            for (String label : labels) {
                AccessibilityNodeInfo node = find(label);
                if (node == null) continue;
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
                while (node != null && !node.isClickable()) node = node.getParent();
                if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("Missing import control: " + labels[0]);
    }
    @SuppressWarnings("unchecked") private void restore(SharedPreferences prefs, Map<String, ?> values) {
        SharedPreferences.Editor edit = prefs.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String) edit.putString(key, (String)value);
            else if (value instanceof Boolean) edit.putBoolean(key, (Boolean)value);
            else if (value instanceof Integer) edit.putInt(key, (Integer)value);
            else if (value instanceof Long) edit.putLong(key, (Long)value);
            else if (value instanceof Float) edit.putFloat(key, (Float)value);
            else if (value instanceof Set) edit.putStringSet(key, (Set<String>)value);
        }
        if (!edit.commit()) throw new AssertionError("Could not restore diagnostic state");
    }
    @Override public void onStart() {
        SharedPreferences prefs = getTargetContext().getSharedPreferences("vpn_profiles", 0);
        Map<String, ?> previous = prefs.getAll();
        SharedPreferences settings = getTargetContext().getSharedPreferences("vpn_app_prefs", 0);
        Map<String, ?> previousSettings = settings.getAll();
        Bundle result = new Bundle();
        int code = 0;
        try {
            android.accessibilityservice.AccessibilityServiceInfo serviceInfo = getUiAutomation().getServiceInfo();
            serviceInfo.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            getUiAutomation().setServiceInfo(serviceInfo);
            String url = arguments.getString("subscription_url");
            if (url == null) throw new AssertionError("Opt-in URL required");
            prefs.edit().clear().commit();
            Intent intent = new Intent().setClassName(getTargetContext().getPackageName(), "com.vpnapp.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity = startActivitySync(intent);
            waitForIdleSync();
            runOnMainSync(() -> getTargetContext().getSystemService(ClipboardManager.class)
                .setPrimaryClip(ClipData.newPlainText("Subscription diagnostic", url)));
            click("Add", "Add Profile");
            click("Import from Clipboard");
            click("Import");
            long end = SystemClock.elapsedRealtime() + 40000;
            int count = 0;
            while (SystemClock.elapsedRealtime() < end) {
                count = new JSONArray(prefs.getString("profiles_json", "[]")).length();
                if (count > 0 || find("Import failed") != null) break;
                SystemClock.sleep(150);
            }
            result.putInt("imported_profiles", count);
            result.putBoolean("release_clipboard_ui", true);
            result.putBoolean("visible_import_error", find("Import failed") != null);
            if (count == 0) throw new AssertionError("No profiles imported by release UI");
            if ("true".equals(arguments.getString("verify_new_ui"))) {
                for (String choice : new String[]{"Name · A–Z", "Ping · low to high", "Default server order"}) {
                    click("Sort profiles");
                    click(choice);
                }
                if (!"DEFAULT".equals(settings.getString("profile_sort", null))) throw new AssertionError("Sort choice was not saved");
                result.putBoolean("sort_menu_choices", true);
            }
            if ("true".equals(arguments.getString("verify_ping_history"))) {
                SystemClock.sleep(4000); // Let the Android clipboard overlay/import toast leave the active window.
                click("Start ping tests");
                SystemClock.sleep(250);
                screenshot("ping-stop-1.1.2");
                click("Stop ping tests", "Stop");
                long stoppedBy = SystemClock.elapsedRealtime() + 5000;
                while (find("Start ping tests") == null && SystemClock.elapsedRealtime() < stoppedBy) SystemClock.sleep(50);
                if (find("Start ping tests") == null) throw new AssertionError("Stop did not restore Ping control");
                result.putBoolean("release_ping_stop", true);
                click("History of usage");
                click("Yesterday");
                long selectedBy = SystemClock.elapsedRealtime() + 5000;
                while (!selected("Yesterday") && SystemClock.elapsedRealtime() < selectedBy) SystemClock.sleep(50);
                if (!selected("Yesterday")) throw new AssertionError("Yesterday filter did not become selected");
                waitForIdleSync();
                if (find("Group by week") == null) throw new AssertionError("Usage history did not open");
                screenshot("history-yesterday-1.1.2");
                click("Back");
                result.putBoolean("release_home_history_yesterday", true);
            }
            code = -1;
        } catch (Throwable error) {
            result.putString("failure", error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            if (activity != null) runOnMainSync(() -> activity.finish());
            restore(prefs, previous);
            restore(settings, previousSettings);
            finish(code, result);
        }
    }
}
