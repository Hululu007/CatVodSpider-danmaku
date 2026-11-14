package com.github.catvod.spider;

import android.content.Context;
import android.widget.FrameLayout;

final class HostLoadingSmoke {
    static final class Player { public String D = ""; }
    static final class Seek extends FrameLayout {
        public boolean u;
        public Player v = new Player();
        int updates;
        public final Runnable p = this::refresh;
        Seek(Context context) { super(context); }
        void refresh() {
            removeCallbacks(p);
            if (!u || v == null) return;
            updates++;
            if (v.D.isEmpty()) post(p); else postDelayed(p, 200);
        }
        @Override protected void onAttachedToWindow() {super.onAttachedToWindow();u=true;refresh();}
        @Override protected void onDetachedFromWindow() {u=false;removeCallbacks(p);super.onDetachedFromWindow();}
    }

    static void run(ChapterSmokeActivity a) throws Exception {
        Seek[] seek = {null}; HostLoadingGuard.State[] state = {null}; FrameLayout[] root = {null};
        a.main(() -> {
            root[0]=new FrameLayout(a);a.setContentView(root[0]);
            seek[0]=new Seek(a);root[0].addView(seek[0]);
            try {state[0]=new HostLoadingGuard.State(seek[0]);} catch(Exception e){throw new RuntimeException(e);}
            state[0].start();
        });
        Thread.sleep(450);
        a.main(() -> {a.check(!seek[0].u,"loading gates native direct refresh");a.check(seek[0].updates<=1,"empty URL immediate repost stops");seek[0].refresh();a.check(seek[0].updates<=1,"host direct refresh cannot restart empty loop");seek[0].v.D="http://fixture";});
        Thread.sleep(450);
        a.main(() -> {a.check(seek[0].u&&seek[0].updates>1,"URL arrival restores native updates");seek[0].v.D="";});
        Thread.sleep(250);
        int[] count={0};a.main(()->{a.check(!seek[0].u,"natural polling gates a new loading cycle");count[0]=seek[0].updates;});
        Thread.sleep(450);
        a.main(() -> {a.check(seek[0].updates==count[0],"next loading cycle stops updates again");root[0].removeView(seek[0]);});
        Thread.sleep(250);
        a.main(() -> {a.check(!seek[0].u&&seek[0].updates==count[0],"detach cancels callbacks");root[0].addView(seek[0]);});
        Thread.sleep(250);
        a.main(() -> {a.check(!seek[0].u,"reattach reapplies loading gate after native attach");seek[0].v.D="http://next";state[0].run();a.check(seek[0].u,"reattached player resumes");state[0].close();a.check(seek[0].u,"close preserves normal native attached state");root[0].removeView(seek[0]);});
        Thread.sleep(250);
        a.main(() -> a.check(!seek[0].u,"host detach still works after guard cleanup"));
        Seek[] second={null};HostLoadingGuard.State[] secondState={null};
        a.main(()->{
            seek[0].v.D="";root[0].addView(seek[0]);
            second[0]=new Seek(a);root[0].addView(second[0]);
            try {state[0]=new HostLoadingGuard.State(seek[0]);secondState[0]=new HostLoadingGuard.State(second[0]);}
            catch(Exception e){throw new RuntimeException(e);}
            state[0].start();secondState[0].start();
        });
        Thread.sleep(250);
        a.main(()->{a.check(!seek[0].u&&!second[0].u,"two loading views keep independent guards");state[0].close(false);count[0]=seek[0].updates;});
        Thread.sleep(250);
        a.main(()->{a.check(!seek[0].u&&seek[0].updates==count[0],"destroy while attached does not restart loading loop");a.check(!second[0].u,"destroying one guard leaves the other protected");secondState[0].close(false);root[0].removeAllViews();});
    }
}
