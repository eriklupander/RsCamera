package com.squeed.rscamera;

import java.nio.ByteBuffer;
import java.util.List;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Size;
import android.os.Bundle;
import android.renderscript.Matrix3f;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

/**
 * Main "camera" activity. Currently overrides onPreviewFrame, performing yuv to rgb(a) conversion
 * using call over JNI to a C function which is at least twice as fast as doing it in Java/Dalvik.
 * 
 * Roughy, the native code need 25-30ms for a single 800x480 frame which the Java version need ~60ms on average with large
 * deviations.
 * 
 * The current implementation uses the "old" way of getting preview frame data, e.g. through the supplied data byte array. This
 * need to be changed so it uses a pre-allocated buffer instead. This is a known problem which makes GC:s happen every two frames,
 * freeing up about 1.1mb of RAM with a latency of ~10ms.
 * 
 * Most of the code in this Activity is a mishmash of stuff from the Android SDK samples, things I found on StackOverflow and the 
 * yuv2rgb conversion is a derivative of what I found in the http://code.google.com/p/andar/ project.
 * 
 * @author Erik
 *
 */
public class RsCameraActivity extends Activity {
	
	static {
        System.loadLibrary("rscamera");
    }
	
	private Preview mPreview;
    Camera mCamera;
    int numberOfCameras;
    int cameraCurrentlyLocked;

    // The first rear facing camera
    int defaultCameraId;
    
//    static RenderScript mRS;
//    static ScriptC_yuvtorgb mYUVScript;
//    static Allocation rsYuvData;
//	static Allocation rsRgbData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
//        mRS = RenderScript.create(this);
//        mYUVScript = new ScriptC_yuvtorgb(mRS, getResources(), R.raw.yuvtorgb);

        // Hide the window title.
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // Create a RelativeLayout container that will hold a SurfaceView,
        // and set it as the content of our activity.
        mPreview = new Preview(this);
        setContentView(mPreview);

        // Find the total number of cameras available
        numberOfCameras = Camera.getNumberOfCameras();

        // Find the ID of the default camera
        CameraInfo cameraInfo = new CameraInfo();
            for (int i = 0; i < numberOfCameras; i++) {
                Camera.getCameraInfo(i, cameraInfo);
                if (cameraInfo.facing == CameraInfo.CAMERA_FACING_BACK) {
                    defaultCameraId = i;
                }
            }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Open the default i.e. the first rear facing camera.
        mCamera = Camera.open();
        cameraCurrentlyLocked = defaultCameraId;
        mPreview.setCamera(mCamera);
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Because the Camera object is a shared resource, it's very
        // important to release it when the activity is paused.
        if (mCamera != null) {
            mPreview.setCamera(null);
            mCamera.release();
            mCamera = null;
        }
    }

  
}

// ----------------------------------------------------------------------

/**
 * A simple wrapper around a Camera and a SurfaceView that renders a centered preview of the Camera
 * to the surface. We need to center the SurfaceView because not all devices have cameras that
 * support preview sizes at the same aspect ratio as the device's display.
 */
class Preview extends ViewGroup implements SurfaceHolder.Callback,
Camera.PreviewCallback {
    private final String TAG = "Preview";

    SurfaceView mSurfaceView;
    SurfaceHolder mHolder;
    Size mPreviewSize;
    List<Size> mSupportedPreviewSizes;
    Camera mCamera;
    
    

	private byte[] rgbData = new byte[480*800*4];
	private ByteBuffer frameBuffer;
	private Bitmap bmp;

	// Used when running yuv2rgb in java code
	//private int[] tmpRgbData = new int[480*800];

	private byte[] callbackBuffer = new byte[(int) (480*800*2)];
    
   

    public Preview(Context context) {
        super(context);
        frameBuffer = makeByteBuffer(480*800*4);
        bmp = Bitmap.createBitmap(800, 480, Bitmap.Config.ARGB_8888);
        setSaturation();
        mSurfaceView = new SurfaceView(context);
        addView(mSurfaceView);

        // Install a SurfaceHolder.Callback so we get notified when the
        // underlying surface is created and destroyed.
        mHolder = mSurfaceView.getHolder();
        mHolder.addCallback(this);
        mHolder.setType(SurfaceHolder.SURFACE_TYPE_NORMAL);
    }
    
    public static ByteBuffer makeByteBuffer(int size) {
		ByteBuffer bb = ByteBuffer.allocateDirect(size);
		bb.position(0);
		return bb;
	}

    public void setCamera(Camera camera) {
        mCamera = camera;
        if (mCamera != null) {
            mSupportedPreviewSizes = mCamera.getParameters().getSupportedPreviewSizes();
            requestLayout();
        }
    }

    public void switchCamera(Camera camera) {
       setCamera(camera);

       Camera.Parameters parameters = camera.getParameters();
       parameters.setPreviewSize(mPreviewSize.width, mPreviewSize.height);
       requestLayout();

       camera.setParameters(parameters);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // We purposely disregard child measurements because act as a
        // wrapper to a SurfaceView that centers the camera preview instead
        // of stretching it.
        final int width = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        final int height = resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec);
        setMeasuredDimension(width, height);

        if (mSupportedPreviewSizes != null) {
            mPreviewSize = getOptimalPreviewSize(mSupportedPreviewSizes, width, height);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (changed && getChildCount() > 0) {
            final View child = getChildAt(0);

            final int width = r - l;
            final int height = b - t;

            int previewWidth = width;
            int previewHeight = height;
            if (mPreviewSize != null) {
                previewWidth = mPreviewSize.width;
                previewHeight = mPreviewSize.height;
            }

            // Center the child SurfaceView within the parent.
            if (width * previewHeight > height * previewWidth) {
                final int scaledChildWidth = previewWidth * height / previewHeight;
                child.layout((width - scaledChildWidth) / 2, 0,
                        (width + scaledChildWidth) / 2, height);
            } else {
                final int scaledChildHeight = previewHeight * width / previewWidth;
                child.layout(0, (height - scaledChildHeight) / 2,
                        width, (height + scaledChildHeight) / 2);
            }
        }
    }

    public void surfaceCreated(SurfaceHolder holder) {
    	setWillNotDraw(true);
    	mCamera.addCallbackBuffer(callbackBuffer);
    	//mCamera.setPreviewCallbackWithBuffer(this);
    	mCamera.setPreviewCallback(this);
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        // Surface will be destroyed when we return, so stop the preview.
        if (mCamera != null) {
            mCamera.stopPreview();
        }
    }


    private Size getOptimalPreviewSize(List<Size> sizes, int w, int h) {
        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double) w / h;
        if (sizes == null) return null;

        Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        int targetHeight = h;

        // Try to find an size match aspect ratio and size
        for (Size size : sizes) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
            if (Math.abs(size.height - targetHeight) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }

        // Cannot find the one match the aspect ratio, ignore the requirement
        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Size size : sizes) {
                if (Math.abs(size.height - targetHeight) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }
        }
        return optimalSize;
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        // Now that the size is known, set up the camera parameters and begin
        // the preview.
    	
    	// YUV use 50% more space than RGB
//    	double yuvSize = w*h*1.5;
//    	RsCameraActivity.rsYuvData = Allocation.createSized(RsCameraActivity.mRS, Element.U8(RsCameraActivity.mRS),new Double(yuvSize).intValue());
//    	RsCameraActivity.rsRgbData = Allocation.createSized(RsCameraActivity.mRS, Element.U8(RsCameraActivity.mRS),new Double(yuvSize).intValue());
    	
        Camera.Parameters parameters = mCamera.getParameters();
        parameters.setPreviewSize(mPreviewSize.width, mPreviewSize.height);
        requestLayout();

        mCamera.setParameters(parameters);
        mCamera.startPreview();
    }
    
    final Paint p1 = new Paint();

	@Override
	public void onPreviewFrame(byte[] data, Camera camera) {
		
		Canvas c = null;

        if(mHolder == null){
            return;
        }

        try {
            synchronized (mHolder) {
        		c = mHolder.lockCanvas();
                
               /* NATIVE BASED YUV2RGB conversion */
        		/* TODO make this more effective. Perhaps feed frameBuffer.array() into the yuv420sp2rgb function directly? */
        		//long start = System.currentTimeMillis();
                yuv420sp2rgb(data, 800, 480, rgbData);
                //Log.i("TAG", "Conversion took " + (System.currentTimeMillis() - start) + " ms.");
                frameBuffer.position(0);
                frameBuffer.put(rgbData);
				frameBuffer.position(0);
               // filter(rgbData);
               
                bmp.copyPixelsFromBuffer(frameBuffer);
                c.drawBitmap(bmp, 0, 0, p1);
                
        		
        		/* JAVA BASED YUV2RGB convert */
//                long start = System.currentTimeMillis();
//                decodeYUV420SP(tmpRgbData, data, 800, 480);
//                Log.i("TAG", "Conversion took " + (System.currentTimeMillis() - start) + " ms.");
//                c.drawBitmap(tmpRgbData, 0, 800, 0, 0, 800, 480, true, new Paint());
                
           
             }
        } finally {
            // do this in a finally so that if an exception is thrown
            // during the above, we don't leave the Surface in an
            // inconsistent state
            if (c != null) {
                mHolder.unlockCanvasAndPost(c);
            }
        }
	}

	/** Java implementation of the yuv2wgb converter. It's at least twice as slow as NDK code on SGS2 */
	static public void decodeYUV420SP(int[] rgb, byte[] yuv420sp, int width, int height) {
    	final int frameSize = width * height;
    	
    	for (int j = 0, yp = 0; j < height; j++) {
    		int uvp = frameSize + (j >> 1) * width, u = 0, v = 0;
    		for (int i = 0; i < width; i++, yp++) {
    			int y = (0xff & ((int) yuv420sp[yp])) - 16;
    			if (y < 0) y = 0;
    			if ((i & 1) == 0) {
    				v = (0xff & yuv420sp[uvp++]) - 128;
    				u = (0xff & yuv420sp[uvp++]) - 128;
    			}
    			
    			int y1192 = 1192 * y;
    			int r = (y1192 + 1634 * v);
    			int g = (y1192 - 833 * v - 400 * u);
    			int b = (y1192 + 2066 * u);
    			
    			if (r < 0) r = 0; else if (r > 262143) r = 262143;
    			if (g < 0) g = 0; else if (g > 262143) g = 262143;
    			if (b < 0) b = 0; else if (b > 262143) b = 262143;
    			
    			rgb[yp] = 0xff000000 | ((r << 6) & 0xff0000) | ((g >> 2) & 0xff00) | ((b >> 10) & 0xff);
    		}
    	}
    }
	
	private float mInBlack = 0.0f;
    private float mOutBlack = 0.0f;
    private float mInWhite = 255.0f;
    private float mOutWhite = 255.0f;
    private float mSaturation = 0.35f;
    float mInWMinInB;
    float mOutWMinOutB;
    float mOverInWMinInB;

    private Matrix3f satMatrix = new Matrix3f();
    
    private void setSaturation() {
        float rWeight = 0.299f;
        float gWeight = 0.587f;
        float bWeight = 0.114f;
        float oneMinusS = 1.0f - mSaturation;
        
        mInWMinInB = mInWhite - mInBlack;
        mOutWMinOutB = mOutWhite - mOutBlack;
        mOverInWMinInB = 1.f / mInWMinInB;

        satMatrix.set(0, 0, oneMinusS * rWeight + mSaturation);
        satMatrix.set(0, 1, oneMinusS * rWeight);
        satMatrix.set(0, 2, oneMinusS * rWeight);
        satMatrix.set(1, 0, oneMinusS * gWeight);
        satMatrix.set(1, 1, oneMinusS * gWeight + mSaturation);
        satMatrix.set(1, 2, oneMinusS * gWeight);
        satMatrix.set(2, 0, oneMinusS * bWeight);
        satMatrix.set(2, 1, oneMinusS * bWeight);
        satMatrix.set(2, 2, oneMinusS * bWeight + mSaturation);
    }
	
//	private void filter(int[] mInPixels) {
//        final float[] m = satMatrix.getArray();
//
//        for (int i=0; i < mInPixels.length; i++) {
//            float r = (float)(mInPixels[i] & 0xff);
//            float g = (float)((mInPixels[i] >> 8) & 0xff);
//            float b = (float)((mInPixels[i] >> 16) & 0xff);
//
//            float tr = r * m[0] + g * m[3] + b * m[6];
//            float tg = r * m[1] + g * m[4] + b * m[7];
//            float tb = r * m[2] + g * m[5] + b * m[8];
//            r = tr;
//            g = tg;
//            b = tb;
//
//            if (r < 0.f) r = 0.f;
//            if (r > 255.f) r = 255.f;
//            if (g < 0.f) g = 0.f;
//            if (g > 255.f) g = 255.f;
//            if (b < 0.f) b = 0.f;
//            if (b > 255.f) b = 255.f;
//
//            r = (r - mInBlack) * mOverInWMinInB;
//            g = (g - mInBlack) * mOverInWMinInB;
//            b = (b - mInBlack) * mOverInWMinInB;
//
//
//            r = (r * mOutWMinOutB) + mOutBlack;
//            g = (g * mOutWMinOutB) + mOutBlack;
//            b = (b * mOutWMinOutB) + mOutBlack;
//
//            if (r < 0.f) r = 0.f;
//            if (r > 255.f) r = 255.f;
//            if (g < 0.f) g = 0.f;
//            if (g > 255.f) g = 255.f;
//            if (b < 0.f) b = 0.f;
//            if (b > 255.f) b = 255.f;
//
//            mInPixels[i] = ((int)r) + (((int)g) << 8) + (((int)b) << 16)
//                            + (mInPixels[i] & 0xff000000);
//        }
//
//    }
	
	
	private native void yuv420sp2rgb(byte[] in, int width, int height, byte[] out);
}
