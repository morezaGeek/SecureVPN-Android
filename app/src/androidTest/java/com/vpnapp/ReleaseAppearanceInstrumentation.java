package com.vpnapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;

/** Uses framework APIs only, so the check also runs against the minified Release APK. */
public class ReleaseAppearanceInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String label) {
        if (node == null) return null;
        if (label.contentEquals(node.getText() == null ? "" : node.getText()) ||
                label.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) return node;
        for (int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo found=find(node.getChild(i),label);
            if(found!=null)return found;
        }
        return null;
    }
    private AccessibilityNodeInfo require(String label) throws Exception {
        long end=android.os.SystemClock.elapsedRealtime()+5000;
        AccessibilityNodeInfo node;
        do {
            node=find(getUiAutomation().getRootInActiveWindow(),label);
            if(node!=null)return node;
            Thread.sleep(100);
        } while(android.os.SystemClock.elapsedRealtime()<end);
        throw new AssertionError("Missing Release UI: "+label);
    }
    private void screenshot(String name) throws Exception {
        Thread.sleep(600);
        File dir=new File(getTargetContext().getExternalFilesDir(null),"review-screenshots");dir.mkdirs();
        Bitmap bitmap=getUiAutomation().takeScreenshot();
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,out);
        } finally {bitmap.recycle();}
    }
    @Override public void onStart() {
        Bundle result=new Bundle(); Activity activity=null;
        try {
            if(!"1.0.1".equals(getTargetContext().getPackageManager().getPackageInfo("com.vpnapp",0).versionName))
                throw new AssertionError("Wrong release version");
            if(!getTargetContext().getSharedPreferences("vpn_profiles",0).getString("profiles_json","[]").equals("[]"))
                throw new AssertionError("Fresh installation contains profiles");
            for(String theme:new String[]{"LIGHT","DARK"}) {
                getTargetContext().getSharedPreferences("vpn_app_prefs",0).edit().putString("theme_mode",theme).commit();
                activity=startActivitySync(new Intent().setClassName("com.vpnapp","com.vpnapp.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                require("No profiles yet");require("v1.0.1");
                screenshot("home-"+theme.toLowerCase());
                AccessibilityNodeInfo node=require("Settings");
                while(node!=null&&!node.isClickable())node=node.getParent();
                if(node==null||!node.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Settings click failed");
                require("Bypass LAN");screenshot("settings-"+theme.toLowerCase());
                final Activity closing=activity;runOnMainSync(closing::finish);activity=null;Thread.sleep(400);
            }
            result.putString("result","Release 1.0.1 launches empty; light/dark home and settings captured without test profiles.");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable error) {
            result.putString("failure",error.toString());finish(Activity.RESULT_CANCELED,result);
        } finally {
            if(activity!=null){final Activity closing=activity;runOnMainSync(closing::finish);}
        }
    }
}
