package com.moments.assistant;

import android.accessibilityservice.*;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.*;
import android.content.*;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import java.util.*;
import java.util.regex.Pattern;

public class WeChatAccessibilityService extends AccessibilityService {
    WindowManager wm; LinearLayout bar; String pending=""; long lastCapture=0;
    final Handler h=new Handler(Looper.getMainLooper());
    final Set<String> noise=new HashSet<>(Arrays.asList(
        "微信","通讯录","发现","我","朋友圈","视频号","搜一搜","扫一扫",
        "点赞","评论","全文","删除","不感兴趣","发表","完成","取消","返回"
    ));
    final Pattern time=Pattern.compile("^(刚刚|昨天|前天|\\d+分钟前|\\d+小时前|\\d+天前|\\d{1,2}:\\d{2})$");

    @Override protected void onServiceConnected(){
        super.onServiceConnected();
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        showBar();
        toast("朋友圈助手服务已启动");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e){
        if(e==null||e.getPackageName()==null||!"com.tencent.mm".contentEquals(e.getPackageName())) return;
        if(Store.sp(this).getBoolean("overlay",true)){
            if(bar==null) showBar();
        }else hideBar();
    }

    @Override public void onInterrupt(){}

    void showBar(){
        if(bar!=null||wm==null)return;
        bar=new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(8,6,8,6);
        bar.setBackgroundColor(0xF2FFFFFF);

        Button c=new Button(this); c.setText("克隆当前"); c.setAllCaps(false);
        Button p=new Button(this); p.setText("去发布"); p.setAllCaps(false);
        Button x=new Button(this); x.setText("×"); x.setAllCaps(false);

        bar.addView(c); bar.addView(p); bar.addView(x);

        c.setOnClickListener(v->{
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            toast("正在读取当前页面…");
            h.postDelayed(this::capture,120);
        });
        p.setOnClickListener(v->{
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            toast("正在打开发布流程…");
            h.postDelayed(this::publish,120);
        });
        x.setOnClickListener(v->{
            Store.sp(this).edit().putBoolean("overlay",false).apply();
            hideBar();
        });

        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(
            -2,-2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        lp.gravity=Gravity.TOP|Gravity.END;
        lp.y=120; lp.x=10;
        try{ wm.addView(bar,lp); }catch(Exception e){ bar=null; toast("悬浮栏创建失败，请重新开启无障碍服务"); }
    }

    void hideBar(){
        if(bar!=null){
            try{wm.removeView(bar);}catch(Exception e){}
            bar=null;
        }
    }

    AccessibilityNodeInfo getWeChatRoot(){
        AccessibilityNodeInfo active=getRootInActiveWindow();
        if(active!=null){
            CharSequence p=active.getPackageName();
            if(p==null || "com.tencent.mm".contentEquals(p)) return active;
        }
        try{
            List<AccessibilityWindowInfo> ws=getWindows();
            if(ws!=null){
                for(AccessibilityWindowInfo w:ws){
                    if(w==null) continue;
                    AccessibilityNodeInfo r=w.getRoot();
                    if(r==null) continue;
                    CharSequence p=r.getPackageName();
                    if(p!=null && "com.tencent.mm".contentEquals(p)) return r;
                }
            }
        }catch(Exception ignored){}
        return active;
    }

    void capture(){
        AccessibilityNodeInfo root=getWeChatRoot();
        if(root==null){
            toast("读取失败：没有拿到微信窗口。请保持微信在前台并重新点一次");
            return;
        }

        ArrayList<String> out=new ArrayList<>();
        collect(root,out);

        LinkedHashSet<String> unique=new LinkedHashSet<>();
        for(String s:out){
            if(s==null) continue;
            s=s.trim();
            if(valid(s)) unique.add(s);
        }

        StringBuilder b=new StringBuilder();
        for(String s:unique){
            if(b.length()>0)b.append("\n");
            b.append(s);
            if(b.length()>1800)break;
        }

        String text=b.toString().trim();
        if(text.length()<2){
            toast("已读取微信窗口，但没有识别到文案。请把目标朋友圈完整显示在屏幕上");
            return;
        }

        if(text.equals(Store.latest(this))&&System.currentTimeMillis()-lastCapture<3000){
            toast("这条内容刚刚已经克隆过");
            return;
        }

        pending=text;
        lastCapture=System.currentTimeMillis();
        Store.saveCapture(this,text);
        copy(text);
        toast("克隆成功，已复制并保存到历史");
    }

    void collect(AccessibilityNodeInfo n,List<String> out){
        if(n==null)return;
        CharSequence t=n.getText();
        if(t!=null)out.add(t.toString());
        CharSequence d=n.getContentDescription();
        if(d!=null&&d.length()>0)out.add(d.toString());
        for(int i=0;i<n.getChildCount();i++){
            try{ collect(n.getChild(i),out); }catch(Exception ignored){}
        }
    }

    boolean valid(String s){
        if(s==null||s.length()<2||noise.contains(s)||time.matcher(s).matches())return false;
        if(s.length()>500)return false;
        if(s.matches("^[·•…\\s]+$"))return false;
        return true;
    }

    void copy(String s){
        ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
            .setPrimaryClip(ClipData.newPlainText("朋友圈文案",s));
    }

    void publish(){
        pending=Store.latest(this);
        if(pending.isEmpty()){toast("请先克隆一条文案");return;}
        Intent i=getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
        if(i!=null){i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);}
        h.postDelayed(()->clickText("发现"),700);
        h.postDelayed(()->clickText("朋友圈"),1500);
        h.postDelayed(this::openComposer,2400);
        h.postDelayed(this::fillEditor,3600);
    }

    boolean clickText(String s){
        AccessibilityNodeInfo r=getWeChatRoot();
        if(r==null)return false;
        List<AccessibilityNodeInfo> a=r.findAccessibilityNodeInfosByText(s);
        for(AccessibilityNodeInfo n:a){
            AccessibilityNodeInfo c=n;
            while(c!=null){
                if(c.isClickable()&&c.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
                c=c.getParent();
            }
        }
        return false;
    }

    void openComposer(){
        if(clickAny(new String[]{"拍照分享","相机","发表朋友圈","发布朋友圈"},true))return;
        AccessibilityNodeInfo r=getWeChatRoot();
        if(r!=null&&contains(r,"朋友圈")){
            int w=getResources().getDisplayMetrics().widthPixels;
            int y=Math.max(90,(int)(getResources().getDisplayMetrics().density*54));
            Path p=new Path();
            p.moveTo(w-(int)(getResources().getDisplayMetrics().density*34),y);
            GestureDescription.StrokeDescription sd=new GestureDescription.StrokeDescription(p,0,900);
            dispatchGesture(new GestureDescription.Builder().addStroke(sd).build(),null,null);
        }
    }

    boolean clickAny(String[] ss,boolean longFirst){
        for(String s:ss){
            AccessibilityNodeInfo r=getWeChatRoot();
            if(r==null)continue;
            List<AccessibilityNodeInfo>a=r.findAccessibilityNodeInfosByText(s);
            for(AccessibilityNodeInfo n:a){
                AccessibilityNodeInfo c=n;
                while(c!=null){
                    if(longFirst&&c.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))return true;
                    if(c.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
                    c=c.getParent();
                }
            }
        }
        return false;
    }

    boolean contains(AccessibilityNodeInfo r,String s){
        if(r==null)return false;
        CharSequence t=r.getText();
        if(t!=null&&t.toString().contains(s))return true;
        for(int i=0;i<r.getChildCount();i++)if(contains(r.getChild(i),s))return true;
        return false;
    }

    void fillEditor(){
        AccessibilityNodeInfo r=getWeChatRoot();
        if(r==null){toast("没有读取到发布页，请手动进入后粘贴");return;}
        AccessibilityNodeInfo e=findEditable(r);
        if(e==null){toast("已复制文案；请手动进入朋友圈发布页后粘贴");return;}
        Bundle b=new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,pending);
        if(!e.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b)){
            copy(pending);
            e.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            e.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        }
        toast("文案已填入，请检查后手动点“发表”");
    }

    AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n){
        if(n==null)return null;
        if(n.isEditable()||"android.widget.EditText".contentEquals(n.getClassName()))return n;
        for(int i=0;i<n.getChildCount();i++){
            AccessibilityNodeInfo x=findEditable(n.getChild(i));
            if(x!=null)return x;
        }
        return null;
    }

    void toast(String s){ h.post(()->Toast.makeText(this,s,Toast.LENGTH_SHORT).show()); }
}
