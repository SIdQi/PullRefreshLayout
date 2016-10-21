package com.mintmedical.refresh;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by JulyYu on 2016/10/6.
 */

public class PlaneDrawable extends RefreshDrawable implements Runnable {

    private boolean isRunning;
    private Handler mHandler = new Handler();

    protected int mOffset;
    protected float mPercent;
    protected int drawableMinddleWidth;
    protected List<Bitmap> bitmaps = new ArrayList<>();
    protected RectF rectF = new RectF();

    public PlaneDrawable(Context context, PullRefreshLayout layout) {
        super(context, layout);
        getBitmaps(context);
    }

    private void getBitmaps(Context context) {
        BitmapDrawable drawable1 = (BitmapDrawable) context.getResources().getDrawable(R.drawable.icon_anim_plane_1);
        drawableMinddleWidth = drawable1.getMinimumWidth() / 2;
        bitmaps.add(drawable1.getBitmap());
        bitmaps.add(((BitmapDrawable) context.getResources().getDrawable(R.drawable.icon_anim_plane_2)).getBitmap());
        bitmaps.add(((BitmapDrawable) context.getResources().getDrawable(R.drawable.icon_anim_plane_3)).getBitmap());
        bitmaps.add(((BitmapDrawable) context.getResources().getDrawable(R.drawable.icon_anim_plane_4)).getBitmap());
        bitmaps.add(((BitmapDrawable) context.getResources().getDrawable(R.drawable.icon_anim_plane_5)).getBitmap());
        bitmaps.add(((BitmapDrawable) context.getResources().getDrawable(R.drawable.icon_anim_plane_6)).getBitmap());
        bitmaps.add(((BitmapDrawable) context.getResources().getDrawable(R.drawable.icon_anim_plane_7)).getBitmap());
        bitmaps.add(((BitmapDrawable) context.getResources().getDrawable(R.drawable.icon_anim_plane_8)).getBitmap());
        bitmaps.add(((BitmapDrawable) context.getResources().getDrawable(R.drawable.icon_anim_plane_9)).getBitmap());
        bitmaps.add(((BitmapDrawable) context.getResources().getDrawable(R.drawable.icon_anim_plane_10)).getBitmap());
        bitmaps.add(((BitmapDrawable) context.getResources().getDrawable(R.drawable.icon_anim_plane_11)).getBitmap());
    }

    @Override
    public void setPercent(float percent) {
        mPercent = percent;
        int centerX = getBounds().centerX();
        rectF.left = centerX - drawableMinddleWidth * mPercent;
        rectF.right = centerX + drawableMinddleWidth * mPercent;
        rectF.top = -drawableMinddleWidth * 2 * mPercent;
        rectF.bottom = 0;
    }

    @Override
    public void setColorSchemeColors(int[] colorSchemeColors) {
    }

    @Override
    public void offsetTopAndBottom(int offset) {
        mOffset += offset;
        invalidateSelf();
    }

    @Override
    public void start() {
        isRunning = true;
        mHandler.postDelayed(this, 50);
    }

    @Override
    public void run() {
        if (isRunning) {
            mHandler.postDelayed(this, 50);
            invalidateSelf();
        }
    }

    @Override
    public void stop() {
        isRunning = false;
        mHandler.removeCallbacks(this);
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    @Override
    public void draw(Canvas canvas) {
        int num = (int) (System.currentTimeMillis() / 50 % 11);
        final int saveCount = canvas.save();
        canvas.translate(0, mOffset);
        Bitmap bitmap = bitmaps.get(num);
        canvas.drawBitmap(bitmap, null, rectF, null);
        canvas.restoreToCount(saveCount);
    }
}
