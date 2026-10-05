package com.wengu.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Preview decoration only. The supplied wallpaper Bitmap is never changed or recycled here. */
public final class LockScreenPreview extends FrameLayout {
    private final ImageView image;
    private final LockOverlay overlay;
    private Bitmap preview;
    private int previewTarget=2;

    public LockScreenPreview(Context context) {
        super(context);
        setMinimumHeight(Math.round(350*getResources().getDisplayMetrics().density));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(true);
        image=new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(image,new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));
        overlay=new LockOverlay(context);
        overlay.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(overlay,new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));
        setPreview(null,2);
    }

    /** target 1 = home screen; target 2 (or both/3) = lock screen illustration. */
    public void setPreview(Bitmap bitmap,int target) { setPreview(bitmap,target,""); }

    public void setPreview(Bitmap bitmap,int target,String summary) {
        preview=bitmap;
        previewTarget=target==1?1:2;
        image.setImageBitmap(bitmap);
        overlay.invalidate();
        String description=previewTarget==1?"桌面壁纸预览":"锁屏预览示意，时钟和通知占位不会写入壁纸";
        if(summary!=null&&!summary.isEmpty())description+="。"+summary;
        setContentDescription(description);
    }

    /** Useful for clearing a failed render while preserving its readable error description. */
    public void clearPreview(String error) {
        setPreview(null,previewTarget);
        if(error!=null&&!error.isEmpty())setContentDescription(error);
    }

    private final class LockOverlay extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds=new RectF();
        LockOverlay(Context context) { super(context); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Bitmap bitmap=preview;
            if(previewTarget==1||bitmap==null||bitmap.isRecycled()||getWidth()==0||getHeight()==0)return;
            // Match ImageView.FIT_CENTER precisely, including letterboxing for other aspect ratios.
            float factor=Math.min((float)getWidth()/bitmap.getWidth(),(float)getHeight()/bitmap.getHeight());
            float width=bitmap.getWidth()*factor,height=bitmap.getHeight()*factor;
            bounds.set((getWidth()-width)/2,(getHeight()-height)/2,(getWidth()+width)/2,(getHeight()+height)/2);
            int save=canvas.save();canvas.clipRect(bounds);
            boolean light=clockBackgroundIsLight(bitmap);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));
            paint.setColor(light?0xFF20332C:0xFFF9FAF6);
            paint.setShadowLayer(width*.008f,0,width*.004f,light?0x70FFFFFF:0xAA000000);
            paint.setTextSize(width*.185f);
            canvas.drawText("09:41",bounds.centerX(),bounds.top+height*.175f,paint);
            paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
            paint.setTextSize(width*.048f);
            canvas.drawText("10月4日  星期日",bounds.centerX(),bounds.top+height*.215f,paint);
            paint.clearShadowLayer();

            // Keep this below the composer's readable text region (which ends at 82%).
            RectF notification=new RectF(bounds.left+width*.075f,bounds.top+height*.855f,bounds.right-width*.075f,bounds.top+height*.916f);
            paint.setColor(0xE8FFFFFF);
            canvas.drawRoundRect(notification,width*.032f,width*.032f,paint);
            paint.setColor(0xFF82928A);
            canvas.drawCircle(notification.left+width*.065f,notification.centerY(),width*.018f,paint);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(0xFF45574D);
            paint.setTextSize(width*.05f);
            Paint.FontMetrics metrics=paint.getFontMetrics();
            canvas.drawText("通知区域",notification.left+width*.115f,notification.centerY()-(metrics.ascent+metrics.descent)/2,paint);
            canvas.restoreToCount(save);
        }

        private boolean clockBackgroundIsLight(Bitmap bitmap) {
            double brightness=0;int count=0;
            for(int y=1;y<=3;y++)for(int x=1;x<=5;x++) {
                int pixel=bitmap.getPixel(Math.min(bitmap.getWidth()-1,Math.round(bitmap.getWidth()*(.25f+x*.08f))),Math.min(bitmap.getHeight()-1,Math.round(bitmap.getHeight()*(.10f+y*.03f))));
                brightness+=WallpaperComposer.luminance(pixel);count++;
            }
            return brightness/count>.42;
        }
    }
}
