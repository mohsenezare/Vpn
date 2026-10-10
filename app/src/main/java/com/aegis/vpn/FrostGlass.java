package com.aegis.vpn;

import android.graphics.*;

/**
 * Lightweight real frosted backdrop for the original v0.5 Canvas UI.
 * The blur is computed once at reduced resolution and reused for each card;
 * it does not blur the full interactive view or animate bitmap allocation.
 */
final class FrostGlass {
    private Bitmap frost;
    private int width, height;
    private boolean wasActive;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    void ensure(float w, float h, boolean active) {
        int iw=Math.max(1,Math.round(w)), ih=Math.max(1,Math.round(h));
        if(frost!=null && iw==width && ih==height && wasActive==active)return;
        width=iw; height=ih; wasActive=active;
        if(frost!=null)frost.recycle();
        final int bw=Math.max(32,(int)(w*.42f)),bh=Math.max(32,(int)(h*.42f));
        Bitmap background=Bitmap.createBitmap(bw,bh,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(background);
        c.scale(bw/w,bh/h);
        c.drawColor(0xfffbfcfc);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(w*.87f,h*.30f,w*.87f,
            new int[]{active?0xc02ce39a:0xc0ffad47,0x00ffffff},null,Shader.TileMode.CLAMP));
        c.drawRect(0,0,w,h,p);p.setShader(null);
        p.setShader(new RadialGradient(-w*.20f,h*.76f,w*.85f,
            new int[]{0x46dceee8,0x00ffffff},null,Shader.TileMode.CLAMP));
        c.drawRect(0,0,w,h,p);p.setShader(null);
        for(int j=0;j<3;j++){
            Path path=new Path();float y=h*(.20f+j*.10f)+8;
            path.moveTo(w+50,y-140);
            path.cubicTo(w*.20f,y+90,w*1.25f,y+150,-50,y+320);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.3f);p.setColor(0xafffffff);
            c.drawPath(path,p);
            p.setStrokeWidth(24);p.setColor(0x17ffffff);c.drawPath(path,p);
        }
        frost=boxBlur(background,6);
        background.recycle();
    }

    private static Bitmap boxBlur(Bitmap source,int radius){
        int w=source.getWidth(),h=source.getHeight();
        int[] src=new int[w*h],tmp=new int[w*h],dst=new int[w*h];
        source.getPixels(src,0,w,0,0,w,h);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int rr=0,gg=0,bb=0,count=0;
            for(int z=-radius;z<=radius;z++){
                int color=src[y*w+Math.max(0,Math.min(w-1,x+z))];
                rr+=(color>>>16)&255;gg+=(color>>>8)&255;bb+=color&255;count++;
            }
            tmp[y*w+x]=0xff000000|((rr/count)<<16)|((gg/count)<<8)|(bb/count);
        }
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int rr=0,gg=0,bb=0,count=0;
            for(int z=-radius;z<=radius;z++){
                int color=tmp[Math.max(0,Math.min(h-1,y+z))*w+x];
                rr+=(color>>>16)&255;gg+=(color>>>8)&255;bb+=color&255;count++;
            }
            dst[y*w+x]=0xff000000|((rr/count)<<16)|((gg/count)<<8)|(bb/count);
        }
        Bitmap result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        result.setPixels(dst,0,w,0,0,w,h);
        return result;
    }

    void draw(Canvas canvas,float x,float y,float w,float h,float radius,int bg,int border,float fullW,float fullH){
        if(frost==null)return;
        paint.reset();paint.setAntiAlias(true);paint.setFilterBitmap(true);
        Path mask=new Path();mask.addRoundRect(x,y,x+w,y+h,radius,radius,Path.Direction.CW);
        canvas.save();canvas.clipPath(mask);
        paint.setAlpha(255);
        canvas.drawBitmap(frost,null,new RectF(0,0,fullW,fullH),paint);
        // Frosted translucency, plus a top-left light reflection on every card.
        paint.setShader(new LinearGradient(x,y,x+w,y+h,
            0x63ffffff,0x25ffffff,Shader.TileMode.CLAMP));
        canvas.drawRect(x,y,x+w,y+h,paint);paint.setShader(null);
        paint.setShader(new LinearGradient(x,y,x,y+h*.55f,
            0x77ffffff,0x00ffffff,Shader.TileMode.CLAMP));
        canvas.drawRect(x,y,x+w,y+h*.55f,paint);paint.setShader(null);
        canvas.restore();
        // Two crisp glass-edge reflections remain legible on pale backgrounds.
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f);
        paint.setColor(0xd5ffffff);
        canvas.drawRoundRect(x+.8f,y+.8f,x+w-.8f,y+h-.8f,radius,radius,paint);
        paint.setStrokeWidth(.8f);paint.setColor(0x42a3aeb9);
        canvas.drawRoundRect(x+2.0f,y+2.0f,x+w-2.0f,y+h-2.0f,Math.max(1,radius-1),Math.max(1,radius-1),paint);
        paint.setStyle(Paint.Style.FILL);
    }
}
