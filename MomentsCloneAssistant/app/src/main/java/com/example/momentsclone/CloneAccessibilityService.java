package com.example.momentsclone;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityService.ScreenshotResult;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.Toast;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public class CloneAccessibilityService extends AccessibilityService {
    private static CloneAccessibilityService instance;
    private WindowManager wm;
    private Button bubble;
    private WindowManager.LayoutParams params;
    private boolean publishMode=false;
    private DraftRepository repo;

    public static CloneAccessibilityService getInstance(){ return instance; }

    @Override protected void onServiceConnected(){
        super.onServiceConnected();
        instance=this;
        repo=new DraftRepository(this);
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        showBubble();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e){}
    @Override public void onInterrupt(){}

    @Override public void onDestroy(){
        hideBubble();
        if(instance==this) instance=null;
        super.onDestroy();
    }

    public void toggleBubble(){ if(bubble==null) showBubble(); else hideBubble(); }
    public void setPublishMode(boolean on){
        publishMode=on;
        if(bubble!=null) bubble.setText(on?"填充":"采集");
    }

    private void showBubble(){
        if(bubble!=null||wm==null) return;
        bubble=new Button(this);
        bubble.setText(publishMode?"填充":"采集");
        bubble.setTextSize(12);
        params=new WindowManager.LayoutParams(
                dp(66),dp(48),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.END;
        params.x=dp(8); params.y=dp(220);

        bubble.setOnClickListener(v->{ if(publishMode) fillPending(); else capture(); });
        bubble.setOnTouchListener(new View.OnTouchListener(){
            float sx,sy; int px,py; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e){
                switch(e.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:
                        sx=e.getRawX(); sy=e.getRawY(); px=params.x; py=params.y; moved=false; return false;
                    case MotionEvent.ACTION_MOVE:
                        float dx=e.getRawX()-sx, dy=e.getRawY()-sy;
                        if(Math.abs(dx)>dp(5)||Math.abs(dy)>dp(5)) moved=true;
                        if(moved){
                            params.x=Math.max(0,px-(int)dx);
                            params.y=Math.max(0,py+(int)dy);
                            wm.updateViewLayout(bubble,params);
                            return true;
                        }
                        break;
                    case MotionEvent.ACTION_UP: return moved;
                }
                return false;
            }
        });
        wm.addView(bubble,params);
    }

    private void hideBubble(){
        if(bubble!=null&&wm!=null){
            try{ wm.removeView(bubble); }catch(Exception ignored){}
            bubble=null;
        }
    }

    private void capture(){
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null||root.getPackageName()==null||!"com.tencent.mm".contentEquals(root.getPackageName())){
            Toast.makeText(this,"请先打开微信朋友圈目标内容",Toast.LENGTH_LONG).show();
            return;
        }
        String text=extractText(root);
        takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback(){
            @Override public void onSuccess(ScreenshotResult r){
                saveDraft(text,saveScreenshot(r));
            }
            @Override public void onFailure(int code){
                saveDraft(text,"");
                Toast.makeText(CloneAccessibilityService.this,"文案已保存，截图失败："+code,Toast.LENGTH_LONG).show();
            }
        });
    }

    private void saveDraft(String text,String uri){
        repo.add(new Draft(UUID.randomUUID().toString(),System.currentTimeMillis(),text,uri));
        Toast.makeText(this,"已加入克隆队列，可继续采集下一条",Toast.LENGTH_LONG).show();
    }

    private String saveScreenshot(ScreenshotResult r){
        HardwareBuffer hb=r.getHardwareBuffer();
        if(hb==null) return "";
        Bitmap wrapped=Bitmap.wrapHardwareBuffer(hb,r.getColorSpace());
        if(wrapped==null){ hb.close(); return ""; }
        Bitmap bm=wrapped.copy(Bitmap.Config.ARGB_8888,false);
        hb.close();
        if(bm==null) return "";

        ContentValues v=new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME,"moments_clone_"+System.currentTimeMillis()+".png");
        v.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
        v.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/MomentsClone");
        v.put(MediaStore.Images.Media.IS_PENDING,1);
        Uri uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);
        if(uri==null) return "";
        try(OutputStream out=getContentResolver().openOutputStream(uri)){
            bm.compress(Bitmap.CompressFormat.PNG,100,out);
            v.clear(); v.put(MediaStore.Images.Media.IS_PENDING,0);
            getContentResolver().update(uri,v,null,null);
            return uri.toString();
        }catch(Exception ex){
            try{ getContentResolver().delete(uri,null,null); }catch(Exception ignored){}
            return "";
        }
    }

    private String extractText(AccessibilityNodeInfo root){
        Set<String> lines=new LinkedHashSet<>();
        walk(root,lines,0);
        StringBuilder b=new StringBuilder();
        for(String s:lines){
            String t=s.replace('\r','\n').trim();
            if(t.isEmpty()||isChrome(t)) continue;
            if(b.length()>0) b.append('\n');
            b.append(t);
        }
        return b.toString().trim();
    }

    private void walk(AccessibilityNodeInfo n,Set<String> out,int d){
        if(n==null||d>40) return;
        CharSequence t=n.getText(), c=n.getContentDescription();
        if(t!=null) out.add(t.toString());
        if(c!=null) out.add(c.toString());
        for(int i=0;i<n.getChildCount();i++){
            AccessibilityNodeInfo ch=n.getChild(i);
            walk(ch,out,d+1);
            if(ch!=null) ch.recycle();
        }
    }

    private boolean isChrome(String s){
        String[] x={"微信","朋友圈","发现","通讯录","聊天","我","发表","取消","完成","赞","评论","更多"};
        for(String e:x) if(s.equals(e)) return true;
        return s.matches("\\d{1,2}:\\d{2}")||s.matches("\\d+%");
    }

    private void fillPending(){
        Draft d=repo.pending();
        if(d==null){ setPublishMode(false); Toast.makeText(this,"没有待发布草稿",Toast.LENGTH_SHORT).show(); return; }
        AccessibilityNodeInfo edit=findEditable(getRootInActiveWindow());
        if(edit==null){ Toast.makeText(this,"请先进入朋友圈发布编辑页",Toast.LENGTH_LONG).show(); return; }
        ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("朋友圈文案",d.text));
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        boolean ok=edit.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        if(!ok){
            android.os.Bundle a=new android.os.Bundle();
            a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,d.text);
            ok=edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,a);
        }
        Toast.makeText(this,ok?"文案已填充，请检查图片并手动确认发表":"填充失败，文案已在剪贴板",Toast.LENGTH_LONG).show();
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n){
        if(n==null) return null;
        if(n.isEditable()||"android.widget.EditText".equals(String.valueOf(n.getClassName()))) return n;
        for(int i=0;i<n.getChildCount();i++){
            AccessibilityNodeInfo ch=n.getChild(i);
            AccessibilityNodeInfo f=findEditable(ch);
            if(f!=null) return f;
            if(ch!=null) ch.recycle();
        }
        return null;
    }

    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }
}
