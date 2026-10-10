package com.aegis.vpn;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.*;
final class GlassDialog {
 static class Builder extends AlertDialog.Builder {
  Builder(Context c){super(c);}
  @Override public AlertDialog show(){
   AlertDialog d=super.show();Window w=d.getWindow();
   if(w!=null){
    float density=getContext().getResources().getDisplayMetrics().density;
    GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xbdfefbf6,0x95ffffff,0xafeffaf5});
    bg.setCornerRadius(30*density);bg.setStroke((int)density,Color.WHITE);
    w.setBackgroundDrawable(bg);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(.24f);
    if(Build.VERSION.SDK_INT>=31){w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);w.getAttributes().setBlurBehindRadius((int)(24*density));w.setAttributes(w.getAttributes());w.setBackgroundBlurRadius((int)(32*density));}
    w.setLayout((int)(getContext().getResources().getDisplayMetrics().widthPixels*.91f),WindowManager.LayoutParams.WRAP_CONTENT);
    View v=w.getDecorView();v.setElevation(20*density);v.setAlpha(0);v.setScaleX(.955f);v.setScaleY(.955f);v.setTranslationY(16*density);v.animate().alpha(1).scaleX(1).scaleY(1).translationY(0).setDuration(340).setInterpolator(new android.view.animation.PathInterpolator(.18f,.75f,.20f,1f)).start();
   }return d;
  }
 }
}
