package com.aegis.vpn;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.*;
import android.view.animation.*;

/** Lightweight translucent iOS-style cards with coordinated fade/slide/scale spring. */
final class GlassDialog {
 static class Builder extends AlertDialog.Builder {
  Builder(Context c){super(c);}
  @Override public AlertDialog show(){
   final AlertDialog d=super.show();
   final Window w=d.getWindow();
   if(w!=null){
    float scale=getContext().getResources().getDisplayMetrics().density;
    GradientDrawable background=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
      new int[]{0xfcfffcf8,0xf8ffffff,0xf3fafff9});
    background.setCornerRadius(scale*31);
    background.setStroke(Math.max(1,(int)scale),0xdfffffff);
    w.setBackgroundDrawable(background);
    w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    WindowManager.LayoutParams params=w.getAttributes();
    params.dimAmount=.30f;w.setAttributes(params);
    if(Build.VERSION.SDK_INT>=31){
      w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
      params=w.getAttributes();
      params.setBlurBehindRadius((int)(19*scale));w.setAttributes(params);
      w.setBackgroundBlurRadius((int)(24*scale));
    }
    w.setLayout((int)(getContext().getResources().getDisplayMetrics().widthPixels*.90f),
        WindowManager.LayoutParams.WRAP_CONTENT);
    View root=w.getDecorView();
    root.setElevation(scale*19);
    root.setAlpha(0);
    root.setScaleX(.94f);root.setScaleY(.94f);
    root.setTranslationY(scale*25);
    root.animate().cancel();
    root.animate().alpha(1).scaleX(1).scaleY(1).translationY(0)
      .setDuration(430)
      .setInterpolator(new OvershootInterpolator(.52f)).start();
    d.setOnDismissListener(dialog->{root.animate().cancel();});
   }
   return d;
  }
 }
}
