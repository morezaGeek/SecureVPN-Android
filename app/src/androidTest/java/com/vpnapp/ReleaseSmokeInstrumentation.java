package com.vpnapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;

/** Framework-only instrumentation, independent of any libraries removed by R8. */
public class ReleaseSmokeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String label) {
        if (node == null) return null;
        if (label.contentEquals(node.getText() == null ? "" : node.getText()) ||
            label.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = find(node.getChild(i), label);
            if (result != null) return result;
        }
        return null;
    }

    private AccessibilityNodeInfo find(String label) { return find(getUiAutomation().getRootInActiveWindow(), label); }
    private void click(String label) {
        AccessibilityNodeInfo node = find(label);
        if (node == null) throw new AssertionError("Missing control " + label);
        while (node != null && !node.isClickable()) node = node.getParent();
        if (node == null || !node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("Cannot click " + label);
    }
    private void collect(AccessibilityNodeInfo node, Rect anchor, List<String> result) {
        if (node == null) return;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        if (bounds.top >= anchor.bottom - 4 && bounds.top < anchor.bottom + 100 &&
            Math.abs(bounds.centerX() - anchor.centerX()) < Math.max(anchor.width(), 50) && node.getText() != null)
            result.add(node.getText().toString());
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), anchor, result);
    }
    private List<String> values(String label) {
        List<String> result = new ArrayList<>();
        AccessibilityNodeInfo node = find(label);
        if (node == null) return result;
        Rect anchor = new Rect(); node.getBoundsInScreen(anchor);
        collect(getUiAutomation().getRootInActiveWindow(), anchor, result);
        return result;
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Activity activity = null;
        try {
            activity = startActivitySync(new Intent().setClassName("com.vpnapp", "com.vpnapp.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Thread.sleep(1000);
            for (int round = 1; round <= 3; round++) {
                click("Connect");
                long deadline = android.os.SystemClock.elapsedRealtime() + 75000;
                long ping = -1;
                boolean exit = false;
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    ping = -1; exit = false;
                    for (String value : values("Ping")) if (value.matches("\\d+ms")) ping = Long.parseLong(value.substring(0, value.length() - 2));
                    for (String value : values("Public IP")) if (value.matches("\\d{1,3}(\\.\\d{1,3}){3}") || value.matches("[0-9a-fA-F:]+:[0-9a-fA-F:]+")) exit = true;
                    if (ping > 0 && exit) break;
                    Thread.sleep(200);
                }
                if (!exit || ping <= 0) throw new AssertionError("Release IP/ping unavailable in round " + round);
                Bundle progress = new Bundle(); progress.putInt("round", round); progress.putLong("warm_ping_ms", ping);
                sendStatus(2, progress);
                click("Disconnect");
                long end = android.os.SystemClock.elapsedRealtime() + 20000;
                while (find("Not Connected") == null && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(100);
                if (find("Not Connected") == null) throw new AssertionError("Disconnect did not complete");
            }
            result.putString("result", "PASS: 3 Release connections returned Public IP and warm ping");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("result", "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            try { if (find("Disconnect") != null) click("Disconnect"); } catch (Throwable ignored) { }
            if (activity != null) activity.finish();
        }
    }
}
