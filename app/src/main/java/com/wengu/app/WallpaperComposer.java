package com.wengu.app;

import android.content.Context;
import android.graphics.*;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import org.json.JSONObject;
import java.io.*;

public final class WallpaperComposer {
    public static final String[] THEME_NAMES={"松林渐变","午夜蓝","暖砂渐变","纯色墨绿","纯色雾白","纯色深灰","水墨山景","纸艺晨光","夜色极光"};
    public static final int[][] COLORS={{0xFF183E34,0xFF68846C},{0xFF111F38,0xFF506583},{0xFF9D7258,0xFFD1B998},{0xFF23473A,0xFF23473A},{0xFFECEADF,0xFFECEADF},{0xFF262C2D,0xFF262C2D},{0xFFEFEDE4,0xFFB9CECA},{0xFFF4D8BF,0xFFB77C5E},{0xFF0A1A30,0xFF133D42}};
    public static final String[] IMAGE_ASSETS={"ink-mountains.png","paper-dawn.png","aurora-night.png"};
    public static Bitmap builtInImage(Context c,int theme,int width,int height)throws IOException {
        if(theme<6||theme>=THEME_NAMES.length)return null;BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;
        try(InputStream in=c.getAssets().open("wallpapers/"+IMAGE_ASSETS[theme-6])){BitmapFactory.decodeStream(in,null,opts);}
        opts.inJustDecodeBounds=false;opts.inSampleSize=1;while(opts.outWidth/(opts.inSampleSize*2)>=width&&opts.outHeight/(opts.inSampleSize*2)>=height)opts.inSampleSize*=2;
        try(InputStream in=c.getAssets().open("wallpapers/"+IMAGE_ASSETS[theme-6])){Bitmap bitmap=BitmapFactory.decodeStream(in,null,opts);if(bitmap==null)throw new IOException("内置壁纸无法读取");return bitmap;}
    }
    public static class Rendered {
        public final Bitmap bitmap; public final boolean photoFallback;
        Rendered(Bitmap b,boolean fallback){bitmap=b;photoFallback=fallback;}
    }
    public static int graphemes(String text) {
        android.icu.text.BreakIterator it=android.icu.text.BreakIterator.getCharacterInstance();it.setText(text);
        int n=0;while(it.next()!=android.icu.text.BreakIterator.DONE)n++;return n;
    }
    public static void validate(JSONObject item) throws IOException {
        if(item.optString("summary").trim().isEmpty())throw new IOException("请写下一句话总结");
        if(graphemes(item.optString("summary"))>120)throw new IOException("总结最多 120 字，请精简后保存");
        if(graphemes(item.optString("body"))>6000)throw new IOException("背景说明最多 6000 字");
        if(graphemes(item.optString("scenario"))>200)throw new IOException("适用场景最多 200 字");
        if(graphemes(item.optString("exception"))>500)throw new IOException("例外和更新最多 500 字");
        org.json.JSONArray tags=item.optJSONArray("tags");
        if(tags!=null){if(tags.length()>8)throw new IOException("标签最多 8 个");for(int i=0;i<tags.length();i++)if(graphemes(tags.optString(i))>20)throw new IOException("单个标签最多 20 字");}
    }
    // Layout's API 23 break-strategy constants have the same values as the API 29 LineBreaker aliases.
    @android.annotation.SuppressLint("WrongConstant")
    public static Rendered render(Context context,JSONObject item,JSONObject cfg,int width,int height,int target) throws IOException {
        String text=item==null?"":item.optString("summary");
        if(item!=null){validate(item);if(item.optBoolean("private"))throw new IOException("私密经验仅保存在应用内");}
        width=Math.max(240,Math.min(2160,width));height=Math.max(480,Math.min(4800,height));
        Bitmap b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(b);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        int theme=item==null?-1:item.optInt("theme",-1);if(theme<0)theme=cfg.optInt("theme",0);theme=Math.floorMod(theme,COLORS.length);
        paint.setShader(new LinearGradient(0,0,width,height,COLORS[theme][0],COLORS[theme][1],Shader.TileMode.CLAMP));canvas.drawRect(0,0,width,height,paint);paint.setShader(null);
        String photo=item==null?"":item.optString("photo");if(photo.isEmpty())photo=cfg.optString("photo");
        boolean fallback=false;
        if(photo.isEmpty()&&theme>=6){Bitmap background=builtInImage(context,theme,width,height);try{float factor=Math.max((float)width/background.getWidth(),(float)height/background.getHeight()),rw=background.getWidth()*factor,rh=background.getHeight()*factor;canvas.drawBitmap(background,null,new RectF((width-rw)/2,(height-rh)/2,(width+rw)/2,(height+rh)/2),paint);}finally{background.recycle();}}
        if(!photo.isEmpty()) {
            Bitmap bg=null;
            try {
                File f=new Store(context).photoFile(photo);BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;BitmapFactory.decodeFile(f.getPath(),opts);
                if(opts.outWidth<=0||opts.outHeight<=0)throw new IOException("照片不可用");
                opts.inJustDecodeBounds=false;opts.inSampleSize=Math.max(1,Math.min(opts.outWidth/width,opts.outHeight/height));bg=BitmapFactory.decodeFile(f.getPath(),opts);
                if(bg==null)throw new IOException("照片不可用");
                float scale=Math.max((float)width/bg.getWidth(),(float)height/bg.getHeight());float rw=bg.getWidth()*scale,rh=bg.getHeight()*scale;
                canvas.drawBitmap(bg,null,new RectF((width-rw)/2,(height-rh)/2,(width+rw)/2,(height+rh)/2),paint);
            }catch(Exception e){fallback=true;}finally{if(bg!=null)bg.recycle();}
        }
        if(text.isEmpty())return new Rendered(b,fallback);
        boolean dark=cfg.optBoolean("dark_text")||(theme==4||theme==6||theme==7)&&photo.isEmpty();
        int color=dark?0xFF1D3027:0xFFF8F7EF;
        float scale=(float)width/Math.max(1,context.getResources().getDisplayMetrics().widthPixels);
        float density=context.getResources().getDisplayMetrics().scaledDensity*scale;
        int textWidth=(int)(width*.80f);TextPaint tp=new TextPaint(Paint.ANTI_ALIAS_FLAG);tp.setColor(color);
        tp.setTypeface(Typeface.create(cfg.optBoolean("serif")?"serif":"sans-serif-medium",Typeface.NORMAL));
        StaticLayout layout=null;int font=Math.max(22,Math.min(40,cfg.optInt("font",28)));
        for(;font>=22;font--) {
            tp.setTextSize(font*density);
            layout=StaticLayout.Builder.obtain(text,0,text.length(),tp,textWidth).setAlignment(Layout.Alignment.ALIGN_CENTER)
                    // Balanced wrapping avoids a short orphan final line while retaining Android's
                    // language-aware line boundaries, punctuation rules and the original full text.
                    .setIncludePad(false).setLineSpacing(font*density*.18f,1).setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build();
            if(layout.getLineCount()<=5&&layout.getHeight()<height*.35f)break;
        }
        if(font<22){b.recycle();throw new IOException("这句话超过了壁纸的 5 行可读区域，请精简或减小字号");}
        float y=height*cfg.optInt(target==2?"lock_y":"home_y",target==2?56:50)/100f;
        float top=Math.max(height*.30f,Math.min(height*.82f-layout.getHeight(),y-layout.getHeight()/2f));
        float left=(width-textWidth)/2f;RectF region=new RectF(left-width*.025f,top-height*.015f,left+textWidth+width*.025f,top+layout.getHeight()+height*.015f);
        // Assess the actual text region, not the average brightness of the whole photograph.
        double minLum=1,maxLum=0,total=0,totalSquare=0;int n=0;
        for(int yy=(int)region.top;yy<region.bottom;yy+=Math.max(1,height/40))for(int xx=(int)region.left;xx<region.right;xx+=Math.max(1,width/30)) {
            double l=luminance(b.getPixel(Math.max(0,Math.min(width-1,xx)),Math.max(0,Math.min(height-1,yy))));
            minLum=Math.min(minLum,l);maxLum=Math.max(maxLum,l);total+=l;totalSquare+=l*l;n++;
        }
        double variance=n==0?0:totalSquare/n-(total/n)*(total/n);float opacity=cfg.optInt("scrim",55)/100f;
        double textLum=luminance(color);double worst=dark?minLum:maxLum;
        if(contrast(textLum,worst)<4.5||variance>.018)opacity=Math.max(opacity,.68f);
        int underlay=dark?Color.WHITE:Color.BLACK;double underLum=dark?1:0;
        if(contrast(textLum,worst*(1-opacity)+underLum*opacity)<4.5)opacity=.94f;
        paint.setColor(underlay);paint.setAlpha((int)(opacity*255));canvas.drawRoundRect(region,width*.028f,width*.028f,paint);paint.setAlpha(255);
        canvas.save();canvas.translate(left,top);layout.draw(canvas);canvas.restore();
        return new Rendered(b,fallback);
    }
    static double luminance(int c){return .2126*linear(Color.red(c)/255d)+.7152*linear(Color.green(c)/255d)+.0722*linear(Color.blue(c)/255d);}
    static double linear(double n){return n<=.04045?n/12.92:Math.pow((n+.055)/1.055,2.4);}
    static double contrast(double a,double b){return (Math.max(a,b)+.05)/(Math.min(a,b)+.05);}
    public static String importPhoto(Context context,android.net.Uri uri) throws IOException {
        File raw=new File(context.getCacheDir(),"photo-import-"+java.util.UUID.randomUUID());Bitmap decoded=null;
        try {
            try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(raw)) {
                if(in==null)throw new IOException("无法读取照片");byte[] buffer=new byte[8192];int len;long bytes=0;
                while((len=in.read(buffer))!=-1){bytes+=len;if(bytes>40*1024*1024L)throw new IOException("照片超过 40MB，请选择较小的图片");out.write(buffer,0,len);}
            }
            BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;BitmapFactory.decodeFile(raw.getPath(),opts);
            if(opts.outWidth<=0||opts.outHeight<=0||opts.outWidth>30000||opts.outHeight>30000)throw new IOException("照片格式无法读取");
            opts.inJustDecodeBounds=false;opts.inSampleSize=Math.max(1,Math.max(opts.outWidth,opts.outHeight)/2600);decoded=BitmapFactory.decodeFile(raw.getPath(),opts);
            if(decoded==null)throw new IOException("照片解码失败");
            // Respect camera orientation before creating the persistent private copy.
            androidx.exifinterface.media.ExifInterface exif=new androidx.exifinterface.media.ExifInterface(raw.getPath());int orientation=exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,1);
            Matrix m=new Matrix();if(orientation==3)m.postRotate(180);else if(orientation==6)m.postRotate(90);else if(orientation==8)m.postRotate(270);
            else if(orientation==2)m.postScale(-1,1);else if(orientation==4)m.postScale(1,-1);
            else if(orientation==5){m.postScale(-1,1);m.postRotate(90);}else if(orientation==7){m.postScale(-1,1);m.postRotate(270);}
            if(!m.isIdentity()){Bitmap rotated=Bitmap.createBitmap(decoded,0,0,decoded.getWidth(),decoded.getHeight(),m,true);if(rotated!=decoded)decoded.recycle();decoded=rotated;}
            String ref="photos/"+java.util.UUID.randomUUID()+".jpg";File f=new Store(context).photoFile(ref);f.getParentFile().mkdirs();
            try(OutputStream out=new FileOutputStream(f)){if(!decoded.compress(Bitmap.CompressFormat.JPEG,92,out))throw new IOException("照片保存失败");}
            return ref;
        }finally{raw.delete();if(decoded!=null)decoded.recycle();}
    }
}
