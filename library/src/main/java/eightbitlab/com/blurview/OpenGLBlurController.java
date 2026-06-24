package eightbitlab.com.blurview;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import eightbitlab.com.blurview.SizeScaler.Size;
import eightbitlab.com.blurview.internal.OpenGLBlurPipeline;

/**
 * BlurController for API 29-30. Synchronous pipe, modelled on the RenderScript path: the target is
 * snapshotted into a software bitmap on the UI thread, blurred by OpenGL into a HardwareBuffer-backed
 * bitmap, and drawn the same frame. All work runs on the UI thread, so the blur never trails the
 * content. The OpenGL blur replaces the deprecated RenderScript blur; the snapshot is a software
 * draw, with the same limitations as the RenderScript path (TextureView content and hardware bitmaps
 * may not render).
 */
@RequiresApi(Build.VERSION_CODES.Q)
class OpenGLBlurController implements BlurController {

    // Swap-chain depth. Two leases are held at once (current + previous, since HWUI's RenderThread may
    // still sample the previous frame's buffer), and the next frame is acquired while both are held -
    // 3 images acquired at the peak. The +1 leaves the EGL producer a buffer to render into. Fewer
    // than 3 makes acquireLatestImage throw once all are held.
    private static final int BUFFER_COUNT = 4;

    private final BlurView blurView;
    private final BlurTarget target;
    private final float scaleFactor;
    private final boolean applyNoise;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final int[] targetLocation = new int[2];
    private final int[] blurViewLocation = new int[2];

    private int overlayColor;
    private float blurRadius = DEFAULT_BLUR_RADIUS;
    private boolean enabled = true;
    @Nullable
    private Drawable frameClearDrawable;

    // Capture only when the target content or this view's on-screen position changed since the last
    // capture. Avoids redundant work and breaks the sibling-BlurView invalidation loop.
    private int lastGeneration = -1;
    private int lastLeft = Integer.MIN_VALUE;
    private int lastTop = Integer.MIN_VALUE;
    private boolean forceNextCapture;

    // Software snapshot, reused across frames as the OpenGL blur input.
    @Nullable
    private Bitmap snapshot;
    @Nullable
    private BlurViewCanvas snapshotCanvas;

    private OpenGLBlurPipeline pipeline;
    private OpenGLBlurPipeline.Lease currentLease;
    private OpenGLBlurPipeline.Lease previousLease;
    @Nullable
    private Bitmap displayBitmap;

    private final ViewTreeObserver.OnPreDrawListener drawListener = () -> {
        saveOnScreenLocation();
        if (pollCaptureNeeded()) {
            captureAndBlur();
        }
        return true;
    };

    // GL resources are released on detach and rebuilt on re-attach.
    private final View.OnAttachStateChangeListener attachStateListener = new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(@NonNull View view) {
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull View view) {
            releaseGl();
        }
    };

    OpenGLBlurController(@NonNull BlurView blurView, @NonNull BlurTarget target, int overlayColor,
                         float scaleFactor, boolean applyNoise) {
        this.blurView = blurView;
        this.target = target;
        this.overlayColor = overlayColor;
        this.scaleFactor = scaleFactor;
        this.applyNoise = applyNoise;
        blurView.setWillNotDraw(false);
        blurView.addOnAttachStateChangeListener(attachStateListener);
        setBlurAutoUpdate(true);
    }

    @Override
    public boolean draw(Canvas canvas) {
        // Don't blur into another BlurView's snapshot; skip to avoid recursive captures.
        if (canvas instanceof BlurViewCanvas) {
            return false;
        }
        Bitmap bitmap = displayBitmap;
        if (enabled && bitmap != null) {
            canvas.save();
            canvas.clipRect(0f, 0f, blurView.getWidth(), blurView.getHeight());
            if (frameClearDrawable != null) {
                frameClearDrawable.draw(canvas);
            }
            canvas.save();
            canvas.scale((float) blurView.getWidth() / bitmap.getWidth(), (float) blurView.getHeight() / bitmap.getHeight());
            canvas.drawBitmap(bitmap, 0f, 0f, paint);
            canvas.restore();
            if (applyNoise) {
                Noise.apply(canvas, blurView.getContext(), blurView.getWidth(), blurView.getHeight());
            }
            if (overlayColor != Color.TRANSPARENT) {
                canvas.drawColor(overlayColor);
            }
            canvas.restore();
        }
        return true;
    }

    // UI thread. Snapshots the target, blurs it, and publishes the result for this frame's draw.
    private void captureAndBlur() {
        SizeScaler sizeScaler = new SizeScaler(scaleFactor);
        int width = blurView.getWidth();
        int height = blurView.getHeight();
        if (!enabled || sizeScaler.isZeroSized(width, height)) {
            return;
        }
        Size scaled = sizeScaler.scale(width, height);
        ensureSnapshot(scaled.width, scaled.height);

        snapshot.eraseColor(Color.TRANSPARENT);
        snapshotCanvas.save();
        float scaleX = (float) width / scaled.width;
        float scaleY = (float) height / scaled.height;
        snapshotCanvas.translate(-relativeLeft() / scaleX, -relativeTop() / scaleY);
        snapshotCanvas.scale(1f / scaleX, 1f / scaleY);
        try {
            target.draw(snapshotCanvas);
        } catch (Exception captureFailed) {
            Log.e("BlurView", "Snapshot capture failed", captureFailed);
        }
        snapshotCanvas.restore();

        ensureGl();
        pipeline.setSize(scaled.width, scaled.height);
        OpenGLBlurPipeline.Lease lease = pipeline.render(snapshot, blurRadius);
        if (lease != null) {
            publishLease(lease);
            // The blurred bitmap is a new instance each frame, so the cached display list must be
            // re-recorded for HWUI to draw it. Invalidate so draw runs this same frame.
            blurView.invalidate();
        }
    }

    private void ensureSnapshot(int width, int height) {
        if (snapshot == null || snapshot.getWidth() != width || snapshot.getHeight() != height) {
            if (snapshot != null) {
                snapshot.recycle();
            }
            snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            snapshotCanvas = new BlurViewCanvas(snapshot);
        }
    }

    private void ensureGl() {
        if (pipeline == null) {
            pipeline = new OpenGLBlurPipeline(BUFFER_COUNT);
        }
    }

    private void publishLease(OpenGLBlurPipeline.Lease lease) {
        // Hold the previous lease one extra frame: HWUI's RenderThread may still sample it.
        if (previousLease != null) {
            previousLease.close();
        }
        previousLease = currentLease;
        currentLease = lease;
        displayBitmap = lease.bitmap;
    }

    @Override
    public void updateBlurViewSize() {
        // No-op. The snapshot and blur resolution are recomputed every frame in captureAndBlur.
    }

    @Override
    public void destroy() {
        setBlurAutoUpdate(false);
        blurView.removeOnAttachStateChangeListener(attachStateListener);
        releaseGl();
    }

    private void releaseGl() {
        displayBitmap = null;
        if (currentLease != null) {
            currentLease.close();
            currentLease = null;
        }
        if (previousLease != null) {
            previousLease.close();
            previousLease = null;
        }
        if (pipeline != null) {
            pipeline.release();
            pipeline = null;
        }
        if (snapshot != null) {
            snapshot.recycle();
            snapshot = null;
            snapshotCanvas = null;
        }
    }

    @Override
    public BlurViewFacade setBlurRadius(float radius) {
        this.blurRadius = radius;
        forceNextCapture = true;
        blurView.invalidate();
        return this;
    }

    @Override
    public BlurViewFacade setOverlayColor(int overlayColor) {
        if (this.overlayColor != overlayColor) {
            this.overlayColor = overlayColor;
            blurView.invalidate();
        }
        return this;
    }

    @Override
    public BlurViewFacade setFrameClearDrawable(@Nullable Drawable frameClearDrawable) {
        this.frameClearDrawable = frameClearDrawable;
        return this;
    }

    @Override
    public BlurViewFacade setBlurEnabled(boolean enabled) {
        this.enabled = enabled;
        forceNextCapture = enabled;
        setBlurAutoUpdate(enabled);
        blurView.invalidate();
        return this;
    }

    @Override
    public BlurViewFacade setBlurAutoUpdate(boolean enabled) {
        ViewTreeObserver viewTreeObserver = blurView.getViewTreeObserver();
        viewTreeObserver.removeOnPreDrawListener(drawListener);
        if (enabled) {
            viewTreeObserver.addOnPreDrawListener(drawListener);
        }
        return this;
    }

    // Returns whether a capture is needed (target content or this view's position changed since the
    // last capture) AND advances the tracking state to the current frame. Relies on
    // saveOnScreenLocation having run for the current frame.
    private boolean pollCaptureNeeded() {
        int generation = target.contentGeneration;
        int left = relativeLeft();
        int top = relativeTop();
        boolean changed = forceNextCapture
                || generation != lastGeneration
                || left != lastLeft
                || top != lastTop;
        forceNextCapture = false;
        lastGeneration = generation;
        lastLeft = left;
        lastTop = top;
        return changed;
    }

    private void saveOnScreenLocation() {
        target.getLocationOnScreen(targetLocation);
        blurView.getLocationOnScreen(blurViewLocation);
    }

    private int relativeLeft() {
        return blurViewLocation[0] - targetLocation[0];
    }

    private int relativeTop() {
        return blurViewLocation[1] - targetLocation[1];
    }
}
