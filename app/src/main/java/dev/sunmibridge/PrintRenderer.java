package dev.sunmibridge;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** Memory-bounded 384-dot thermal renderer. PDF pages are rendered in 256-row bands. */
public final class PrintRenderer {
    private PrintRenderer(){}
    public interface Cancellation {boolean canceled();}
    private static final int WIDTH=384, BAND=256;
    private static final int MAX_PAGE_ROWS=50000;
    // 8x8 Bayer threshold matrix, deterministic across strip boundaries.
    private static final int[][] BAYER={
        {0,48,12,60,3,51,15,63},
        {32,16,44,28,35,19,47,31},
        {8,56,4,52,11,59,7,55},
        {40,24,36,20,43,27,39,23},
        {2,50,14,62,1,49,13,61},
        {34,18,46,30,33,17,45,29},
        {10,58,6,54,9,57,5,53},
        {42,26,38,22,41,25,37,21}
    };

    public static String detect(File file)throws IOException {
        byte[] magic=new byte[12];
        try(FileInputStream in=new FileInputStream(file)){
            int n=in.read(magic);
            if(n<4)return null;
            if(magic[0]=='%'&&magic[1]=='P'&&magic[2]=='D'&&magic[3]=='F')return "application/pdf";
            if((magic[0]&255)==0x89&&magic[1]=='P'&&magic[2]=='N'&&magic[3]=='G')return "image/png";
            if((magic[0]&255)==0xff&&(magic[1]&255)==0xd8)return "image/jpeg";
        }
        return null;
    }
    public static void print(File doc,String mime,SunmiDriver driver,Cancellation cancel)throws Exception {
        String actual=detect(doc);
        if(actual==null||!actual.equals(mime))throw new IOException("Unsupported or mislabeled print document");
        if("application/pdf".equals(actual))printPdf(doc,driver,cancel);
        else printImage(doc,driver,cancel);
        if(!cancel.canceled())driver.finishJob();
    }
    private static void printPdf(File file,SunmiDriver driver,Cancellation cancel)throws Exception {
        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
            PdfRenderer reader=new PdfRenderer(fd)) {
            for(int p=0;p<reader.getPageCount()&&!cancel.canceled();p++){
                try(PdfRenderer.Page page=reader.openPage(p)){
                    int pageWidth=page.getWidth(), pageHeight=page.getHeight();
                    if(pageWidth<=0||pageHeight<=0)throw new IOException("Invalid PDF page size");
                    // A printer-sized 58mm PDF includes 5mm unprintable margins on each side.
                    // Wider PDFs (e.g. A4) are scaled to fit 48mm instead of cropped.
                    boolean receipt=pageWidth<=230;
                    float scale=(receipt?464f:WIDTH)/(float)pageWidth;
                    float cropX=receipt?40f:0f;
                    long total=(long)Math.ceil(pageHeight*scale);
                    if(total>MAX_PAGE_ROWS)throw new IOException("PDF page too long for receipt printer");
                    for(int y=0;y<total&&!cancel.canceled();y+=BAND){
                        int h=(int)Math.min(BAND,total-y);
                        Bitmap bitmap=Bitmap.createBitmap(WIDTH,h,Bitmap.Config.ARGB_8888);
                        try{
                            bitmap.eraseColor(Color.WHITE);
                            Matrix m=new Matrix();m.setScale(scale,scale);m.postTranslate(-cropX,-y);
                            page.render(bitmap,new Rect(0,0,WIDTH,h),m,PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                            monochrome(bitmap,y);
                            driver.printStrip(bitmap);
                        } finally {bitmap.recycle();}
                    }
                }
            }
        }
    }
    private static void printImage(File file,SunmiDriver driver,Cancellation cancel)throws Exception {
        BitmapRegionDecoder decoder=BitmapRegionDecoder.newInstance(file.getAbsolutePath(),false);
        if(decoder==null)throw new IOException("Cannot decode image");
        try{
            final int srcW=decoder.getWidth(), srcH=decoder.getHeight();
            if(srcW<=0||srcH<=0)throw new IOException("Invalid image size");
            long rows=(long)Math.ceil(srcH*(double)WIDTH/srcW);
            if(rows>MAX_PAGE_ROWS)throw new IOException("Image too long for receipt printer");
            int sample=1;
            while(sample*2<=srcW/WIDTH)sample*=2;
            BitmapFactory.Options opts=new BitmapFactory.Options();
            opts.inPreferredConfig=Bitmap.Config.ARGB_8888;
            opts.inSampleSize=sample;
            Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
            for(int y=0;y<rows&&!cancel.canceled();y+=BAND){
                int h=(int)Math.min(BAND,rows-y);
                double srcPerDot=(double)srcW/WIDTH;
                int top=(int)Math.floor(y*srcPerDot);
                int bottom=(int)Math.ceil((y+h)*srcPerDot);
                top=Math.max(0,Math.min(srcH-1,top));
                bottom=Math.max(top+1,Math.min(srcH,bottom));
                Bitmap region=decoder.decodeRegion(new Rect(0,top,srcW,bottom),opts);
                if(region==null)throw new IOException("Cannot decode image strip");
                Bitmap output=Bitmap.createBitmap(WIDTH,h,Bitmap.Config.ARGB_8888);
                try{
                    output.eraseColor(Color.WHITE);
                    Canvas canvas=new Canvas(output);
                    float start=(float)(top/srcPerDot-y);
                    float end=(float)(bottom/srcPerDot-y);
                    canvas.drawBitmap(region,null,new RectF(0,start,WIDTH,end),paint);
                    monochrome(output,y);
                    driver.printStrip(output);
                } finally {region.recycle();output.recycle();}
            }
        } finally {decoder.recycle();}
    }
    private static void monochrome(Bitmap bitmap,int yOffset){
        int w=bitmap.getWidth(),h=bitmap.getHeight();
        int[] data=new int[w*h];
        bitmap.getPixels(data,0,w,0,0,w,h);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int p=data[y*w+x];
            int alpha=Color.alpha(p);
            int gray=(77*Color.red(p)+150*Color.green(p)+29*Color.blue(p))>>8;
            gray=(gray*alpha+255*(255-alpha))/255;
            int threshold=128+(BAYER[(y+yOffset)&7][x&7]-32)*3;
            data[y*w+x]=gray<threshold?Color.BLACK:Color.WHITE;
        }
        bitmap.setPixels(data,0,w,0,0,w,h);
    }
}
