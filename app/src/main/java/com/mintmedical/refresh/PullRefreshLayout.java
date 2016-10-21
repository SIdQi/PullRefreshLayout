package com.mintmedical.refresh;

import android.content.Context;
import android.support.v4.view.MotionEventCompat;
import android.support.v4.view.ViewCompat;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.Transformation;
import android.widget.AbsListView;
import android.widget.ImageView;

/**
 * Created by SidHu on 2016/10/16.
 */
public class PullRefreshLayout extends ViewGroup {
    private static final float DECELERATE_INTERPOLATION_FACTOR = 2f;
    private static final int DRAG_MAX_DISTANCE = 64;
    private static final int INVALID_POINTER = -1;
    private static final float DRAG_RATE = .5f;

    private View mTarget;
    private ImageView mRefreshView;
    private ImageView mLoadView;
    private Interpolator mDecelerateInterpolator;
    private int mTouchSlop;
    private int mSpinnerFinalOffset;
    private int mTotalDragDistance;
    private RefreshDrawable mRefreshDrawable;
    private RefreshDrawable mLoadDrawable;
    private int mCurrentOffsetTop;
    private boolean mRefreshing;
    private boolean mLoading;
    private int mActivePointerId;
    private boolean mIsBeingDragged;
    private float mInitialMotionY;
    private int mFrom;
    private boolean mNotify;
    private OnRefreshListener mRefreshListener;
    private OnLoadListener mLoadLisener;

    public int mDurationToStartPosition;
    public int mDurationToCorrectPosition;
    private int mInitialOffsetTop;
    private boolean mDispatchTargetTouchDown;
    private float mDragPercent;

    private RefreshMode mMode = RefreshMode.getDefault();
    //之前手势的方向，为了解决同一个触点前后移动方向不同导致后一个方向会刷新的问题，
    //这里Mode.DISABLED无意义，只是一个初始值，和上拉/下拉方向进行区分
    private RefreshMode mLastDirection = RefreshMode.DISABLED;
    //当子控件移动到尽头时才开始计算初始点的位置
    private float mStartPoint;
    private boolean up;
    private boolean down;

    public PullRefreshLayout(Context context) {
        this(context, null);
    }

    public PullRefreshLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDecelerateInterpolator = new DecelerateInterpolator(DECELERATE_INTERPOLATION_FACTOR);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        int defaultDuration = getResources().getInteger(android.R.integer.config_mediumAnimTime);
        mDurationToStartPosition = defaultDuration;
        mDurationToCorrectPosition = defaultDuration;
        mSpinnerFinalOffset = mTotalDragDistance = dp2px(DRAG_MAX_DISTANCE);

        mRefreshView = new ImageView(context);
        setRefreshDrawable(new PlaneDrawable(getContext(), this));
        mRefreshView.setVisibility(GONE);
        addView(mRefreshView, 0);

        mLoadView = new ImageView(context);
        setLoadDrawable(new PlaneLoadDrawable(getContext(), this));
        mLoadView.setVisibility(GONE);
        addView(mLoadView, 0);

        setWillNotDraw(false);
        ViewCompat.setChildrenDrawingOrderEnabled(this, true);
    }

    public void setRefreshDrawable(RefreshDrawable drawable) {
        setRefreshing(false);
        mRefreshDrawable = drawable;
        mRefreshView.setImageDrawable(mRefreshDrawable);
    }

    public void setLoadDrawable(RefreshDrawable drawable) {
        setLoading(false);
        mLoadDrawable = drawable;
        mLoadView.setImageDrawable(mLoadDrawable);
    }

    public int getFinalOffset() {
        return mSpinnerFinalOffset;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        ensureTarget();
        if (mTarget == null)
            return;

        widthMeasureSpec = MeasureSpec.makeMeasureSpec(getMeasuredWidth() - getPaddingRight() - getPaddingLeft(), MeasureSpec.EXACTLY);
        heightMeasureSpec = MeasureSpec.makeMeasureSpec(getMeasuredHeight() - getPaddingTop() - getPaddingBottom(), MeasureSpec.EXACTLY);
        mTarget.measure(widthMeasureSpec, heightMeasureSpec);
        mRefreshView.measure(widthMeasureSpec, heightMeasureSpec);
        mLoadView.measure(widthMeasureSpec, heightMeasureSpec);
    }

    private void ensureTarget() {
        if (mTarget != null)
            return;
        if (getChildCount() > 0) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child != mRefreshView && child != mLoadView)
                    mTarget = child;
            }
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!isEnabled() || (canChildScrollUp() && canChildScrollDown() && !mRefreshing)) {
            return false;
        }

        final int action = MotionEventCompat.getActionMasked(ev);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (!mRefreshing || !mLoading) {
                    setTargetOffsetTop(0, true);
                }
                mActivePointerId = MotionEventCompat.getPointerId(ev, 0);
                mIsBeingDragged = false;
                final float initialMotionY = getMotionEventY(ev, mActivePointerId);
                if (initialMotionY == -1) {
                    return false;
                }
                mInitialMotionY = initialMotionY;
                mInitialOffsetTop = mCurrentOffsetTop;
                mDispatchTargetTouchDown = false;
                mDragPercent = 0;
                mStartPoint = mInitialMotionY;

                //这里用up/down记录子控件能否下拉，如果当前子控件不能上下滑动，但当手指按下并移动子控件时，控件就会变得可滑动
                //后面的一些处理不能直接使用canChildScrollUp/canChildScrollDown
                //但仍存在问题：当数据不满一屏且设置可以上拉模式后，多次快速上拉会激发上拉加载
                up = canChildScrollUp();
                down = canChildScrollDown();
                break;
            case MotionEvent.ACTION_MOVE:
                if (mActivePointerId == INVALID_POINTER) {
                    return false;
                }
                final float y = getMotionEventY(ev, mActivePointerId);
                if (y == -1) {
                    return false;
                }

                final float yDiff = y - mStartPoint;

                if((mLastDirection == RefreshMode.PULL_FROM_START && yDiff < 0) ||
                        (mLastDirection == RefreshMode.PULL_FROM_END && yDiff > 0))
                {
                    return false;
                }
                //下拉或上拉时，子控件本身能够滑动时，记录当前手指位置，当其滑动到尽头时，
                //mStartPoint作为下拉刷新或上拉加载的手势起点
                if ((canChildScrollUp() && yDiff > 0) || (canChildScrollDown() && yDiff < 0)) {
                    mStartPoint = y;
                }
                //下拉
                if (yDiff > mTouchSlop) {
                    //若当前子控件能向下滑动，或者上个手势为上拉，则返回
                    if (canChildScrollUp() || mMode == RefreshMode.PULL_FROM_END) {
                        mIsBeingDragged = false;
                        return false;
                    }
                    if ((mMode == RefreshMode.PULL_FROM_START) || (mMode == RefreshMode.BOTH)) {
                        mIsBeingDragged = true;
                        mLastDirection = RefreshMode.PULL_FROM_START;
                    }
                }
                //上拉
                else if (-yDiff > mTouchSlop) {
                    //若当前子控件能向上滑动，或者上个手势为下拉，则返回
                    if (canChildScrollDown() || mMode == RefreshMode.PULL_FROM_START) {
                        mIsBeingDragged = false;
                        return false;
                    }
                    //若子控件不能上下滑动，说明数据不足一屏，若不满屏不加载，返回
                    if (!up && !down) {
                        mIsBeingDragged = false;
                        return false;
                    }
                    if ((mMode == RefreshMode.PULL_FROM_END) || (mMode == RefreshMode.BOTH)) {
                        mIsBeingDragged = true;
                        mLastDirection = RefreshMode.PULL_FROM_END;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mIsBeingDragged = false;
                mActivePointerId = INVALID_POINTER;
                mLastDirection = RefreshMode.DISABLED;
                break;
            case MotionEventCompat.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;
        }

        return mIsBeingDragged;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {

        if (!mIsBeingDragged) {
            return super.onTouchEvent(ev);
        }

        final int action = MotionEventCompat.getActionMasked(ev);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mInitialMotionY = ev.getY();
                mActivePointerId = MotionEventCompat.getPointerId(ev, 0);
                mIsBeingDragged = false;
                mDragPercent = 0;
                mStartPoint = mInitialMotionY;

                up = canChildScrollUp();
                down = canChildScrollDown();
                break;
            case MotionEvent.ACTION_MOVE: {
                final int pointerIndex = MotionEventCompat.findPointerIndex(ev, mActivePointerId);
                if (pointerIndex < 0) {
                    return false;
                }

                final float y = MotionEventCompat.getY(ev, pointerIndex);
                final float yDiff = y - mStartPoint;

                if((mLastDirection == RefreshMode.PULL_FROM_START && yDiff < 0)
                        || (mLastDirection == RefreshMode.PULL_FROM_END && yDiff > 0)) {
                    return true;
                }

                if (!mIsBeingDragged && (yDiff > 0 && mLastDirection == RefreshMode.PULL_FROM_START)
                        || (yDiff < 0 && mLastDirection == RefreshMode.PULL_FROM_END)) {
                    mIsBeingDragged = true;
                }

                int targetY;
                if (mRefreshing || mLoading) {
                    targetY = (int) (mInitialOffsetTop + yDiff);
                    if ((mRefreshing && canChildScrollUp()) || (mLoading && canChildScrollDown())) {
                        targetY = -1;
                        mInitialMotionY = y;
                        mInitialOffsetTop = 0;
                        if (mDispatchTargetTouchDown) {
                            mTarget.dispatchTouchEvent(ev);
                        } else {
                            MotionEvent obtain = MotionEvent.obtain(ev);
                            obtain.setAction(MotionEvent.ACTION_DOWN);
                            mDispatchTargetTouchDown = true;
                            mTarget.dispatchTouchEvent(obtain);
                        }
                    } else {
                        if (targetY < 0) {
                            if (mDispatchTargetTouchDown) {
                                mTarget.dispatchTouchEvent(ev);
                            } else {
                                MotionEvent obtain = MotionEvent.obtain(ev);
                                obtain.setAction(MotionEvent.ACTION_DOWN);
                                mDispatchTargetTouchDown = true;
                                mTarget.dispatchTouchEvent(obtain);
                            }
                            targetY = 0;
                        } else if (targetY > mTotalDragDistance) {
                            targetY = mTotalDragDistance;
                        } else {
                            if (mDispatchTargetTouchDown) {
                                MotionEvent obtain = MotionEvent.obtain(ev);
                                obtain.setAction(MotionEvent.ACTION_CANCEL);
                                mDispatchTargetTouchDown = false;
                                mTarget.dispatchTouchEvent(obtain);
                            }
                        }
                    }
                    setTargetOffsetTop(targetY - mCurrentOffsetTop, true);
                } else {
                    final float scrollTop = yDiff * DRAG_RATE;
                    float originalDragPercent = scrollTop / mTotalDragDistance;
                    mDragPercent = Math.min(1f, Math.abs(originalDragPercent));//拖动的百分比
                    float extraOS = Math.abs(scrollTop) - mTotalDragDistance;//弹簧效果的位移
                    float slingshotDist = mSpinnerFinalOffset;
                    //当弹簧效果位移小余0时，tensionSlingshotPercent为0，否则取弹簧位移于总高度的比值，最大为2
                    float tensionSlingshotPercent = Math.max(0, Math.min(extraOS, slingshotDist * 2) / slingshotDist);
                    //对称轴为tensionSlingshotPercent = 2的二次函数，0到2递增
                    float tensionPercent = (float) ((tensionSlingshotPercent / 4) - Math.pow((tensionSlingshotPercent / 4), 2)) * 2f;
                    float extraMove = (slingshotDist) * tensionPercent * 2;
                    targetY = (int) ((slingshotDist * mDragPercent) + extraMove);
                    if (originalDragPercent < 0) {
                        //上拉加载
                        if (mLoadView.getVisibility() != VISIBLE) {
                            mLoadView.setVisibility(VISIBLE);
                        }
                        if (Math.abs(scrollTop) < mTotalDragDistance) {
                            mLoadDrawable.setPercent(mDragPercent);
                        }
                        setTargetOffsetTop(-targetY - mCurrentOffsetTop, true);
                    } else {
                        //下拉刷新
                        targetY = (int) ((slingshotDist * mDragPercent) + extraMove);
                        if (mRefreshView.getVisibility() != View.VISIBLE) {
                            mRefreshView.setVisibility(View.VISIBLE);
                        }
                        if (scrollTop < mTotalDragDistance) {
                            mRefreshDrawable.setPercent(mDragPercent);
                        }
                        setTargetOffsetTop(targetY - mCurrentOffsetTop, true);
                    }
                }
                break;
            }
            case MotionEventCompat.ACTION_POINTER_DOWN:
                final int index = MotionEventCompat.getActionIndex(ev);
                mActivePointerId = MotionEventCompat.getPointerId(ev, index);
                break;
            case MotionEventCompat.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mActivePointerId == INVALID_POINTER) {
                    return false;
                }
                if (mRefreshing || mLoading) {
                    if (mDispatchTargetTouchDown) {
                        mTarget.dispatchTouchEvent(ev);
                        mDispatchTargetTouchDown = false;
                    }
                    return false;
                }
                final int pointerIndex = MotionEventCompat.findPointerIndex(ev, mActivePointerId);
                final float y = MotionEventCompat.getY(ev, pointerIndex);
                final float overscrollTop = (y - mInitialMotionY) * DRAG_RATE;
                mIsBeingDragged = false;
                if (overscrollTop > mTotalDragDistance && mCurrentOffsetTop > mTotalDragDistance) {
                    setRefreshing(true, true);
                    mLastDirection = RefreshMode.PULL_FROM_START;
                }
                else if (Math.abs(overscrollTop) > mTotalDragDistance && mCurrentOffsetTop < -mTotalDragDistance){
                    setLoading(true);
                    mLastDirection = RefreshMode.PULL_FROM_END;
                }
                else {
                    mRefreshing = false;
                    animateOffsetToStartPosition();
                }
                mActivePointerId = INVALID_POINTER;
                mLastDirection = RefreshMode.DISABLED;
                return false;
            }
        }

        return true;
    }

    public void setDurations(int durationToStartPosition, int durationToCorrectPosition) {
        mDurationToStartPosition = durationToStartPosition;
        mDurationToCorrectPosition = durationToCorrectPosition;
    }

    private void animateOffsetToStartPosition() {
        mFrom = mCurrentOffsetTop;
        mAnimateToStartPosition.reset();
        mAnimateToStartPosition.setDuration(mDurationToStartPosition);
        mAnimateToStartPosition.setInterpolator(mDecelerateInterpolator);
        mAnimateToStartPosition.setAnimationListener(mToStartListener);
        mRefreshView.clearAnimation();
        mRefreshView.startAnimation(mAnimateToStartPosition);
    }

    private void animateOffsetToCorrectPosition() {
        mFrom = mCurrentOffsetTop;
        mAnimateToCorrectPosition.reset();
        mAnimateToCorrectPosition.setDuration(mDurationToCorrectPosition);
        mAnimateToCorrectPosition.setInterpolator(mDecelerateInterpolator);
        mAnimateToCorrectPosition.setAnimationListener(mRefreshAnimationListener);
        mRefreshView.clearAnimation();
        mRefreshView.startAnimation(mAnimateToCorrectPosition);
    }

    private void animateLoadOffsetToCorrectPosition() {
        mFrom = mCurrentOffsetTop;
        mAnimateLoadToCorrectPosition.reset();
        mAnimateLoadToCorrectPosition.setDuration(mDurationToCorrectPosition);
        mAnimateLoadToCorrectPosition.setInterpolator(mDecelerateInterpolator);
        mAnimateLoadToCorrectPosition.setAnimationListener(mRefreshLoadAnimationListener);
        mLoadView.clearAnimation();
        mLoadView.startAnimation(mAnimateLoadToCorrectPosition);
    }

    private final Animation mAnimateToCorrectPosition = new Animation() {
        @Override
        public void applyTransformation(float interpolatedTime, Transformation t) {
            int endTarget = mSpinnerFinalOffset;
            int targetTop = (mFrom + (int) ((endTarget - mFrom) * interpolatedTime));
            int offset = targetTop - mTarget.getTop();
            setTargetOffsetTop(offset, false /* requires update */);
        }
    };

    private final Animation mAnimateLoadToCorrectPosition = new Animation() {
        @Override
        public void applyTransformation(float interpolatedTime, Transformation t) {
            int endTarget = -mSpinnerFinalOffset;
            int targetTop = (mFrom + (int) ((endTarget - mFrom) * interpolatedTime));
            int offset = targetTop - mTarget.getTop();
            setTargetOffsetTop(offset, false /* requires update */);
        }
    };

    private final Animation mAnimateToStartPosition = new Animation() {
        @Override
        public void applyTransformation(float interpolatedTime, Transformation t) {
            moveToStart(interpolatedTime);
        }
    };

    private void moveToStart(float interpolatedTime) {
        int targetTop = mFrom - (int) (mFrom * interpolatedTime);
        int offset = targetTop - mTarget.getTop();
        setTargetOffsetTop(offset, false);
        if (offset > 0) {
            mRefreshDrawable.setPercent(mDragPercent * (1 - interpolatedTime));
        } else {
            mLoadDrawable.setPercent(mDragPercent * (1 - interpolatedTime));
        }
    }

    public void setRefreshing(boolean refreshing) {
        if (mRefreshing != refreshing) {
            setRefreshing(refreshing, false /* notify */);
        }
    }

    private void setRefreshing(boolean refreshing, final boolean notify) {
        if (mRefreshing != refreshing) {
            mNotify = notify;
            ensureTarget();
            mRefreshing = refreshing;
            if (mRefreshing) {
                mRefreshDrawable.setPercent(1f);
                animateOffsetToCorrectPosition();
            } else {
                mLastDirection = RefreshMode.DISABLED;
                animateOffsetToStartPosition();
            }
        }
    }

    public void setLoading(boolean loading) {
        if (mLoading != loading) {
            ensureTarget();
            mLoading = loading;
            if (mLoading) {
                mLoadDrawable.setPercent(1f);
                animateLoadOffsetToCorrectPosition();
            } else {
                mLastDirection = RefreshMode.DISABLED;
                animateOffsetToStartPosition();
            }
        }
    }

    private Animation.AnimationListener mRefreshAnimationListener = new Animation.AnimationListener() {
        @Override
        public void onAnimationStart(Animation animation) {
            mRefreshView.setVisibility(View.VISIBLE);
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
        }

        @Override
        public void onAnimationEnd(Animation animation) {
            if (mRefreshing) {
                mRefreshDrawable.start();
                if (mNotify) {
                    if (mRefreshListener != null) {
                        mRefreshListener.onRefresh();
                    }
                }
            } else {
                mRefreshDrawable.stop();
                mRefreshView.setVisibility(View.GONE);
                animateOffsetToStartPosition();
            }
            mCurrentOffsetTop = mTarget.getTop();
            mLastDirection = RefreshMode.DISABLED;
        }
    };

    private Animation.AnimationListener mRefreshLoadAnimationListener = new Animation.AnimationListener() {
        @Override
        public void onAnimationStart(Animation animation) {
            mLoadView.setVisibility(View.VISIBLE);
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
        }

        @Override
        public void onAnimationEnd(Animation animation) {
            if (mLoading) {
                mLoadDrawable.start();
                if (mLoadLisener != null) {
                    mLoadLisener.onLoad();
                }
            } else {
                mLoadDrawable.stop();
                mLoadView.setVisibility(GONE);
                animateOffsetToStartPosition();
            }
            mCurrentOffsetTop = mTarget.getTop();
            mLastDirection = RefreshMode.DISABLED;
        }
    };

    private Animation.AnimationListener mToStartListener = new Animation.AnimationListener() {
        @Override
        public void onAnimationStart(Animation animation) {
            mRefreshDrawable.stop();
            mLoadDrawable.stop();
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
        }

        @Override
        public void onAnimationEnd(Animation animation) {
            mRefreshView.setVisibility(View.GONE);
            mLoadView.setVisibility(GONE);
            mCurrentOffsetTop = mTarget.getTop();
        }
    };

    private void onSecondaryPointerUp(MotionEvent ev) {
        final int pointerIndex = MotionEventCompat.getActionIndex(ev);
        final int pointerId = MotionEventCompat.getPointerId(ev, pointerIndex);
        if (pointerId == mActivePointerId) {
            final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            mActivePointerId = MotionEventCompat.getPointerId(ev, newPointerIndex);
        }
    }

    private float getMotionEventY(MotionEvent ev, int activePointerId) {
        final int index = MotionEventCompat.findPointerIndex(ev, activePointerId);
        if (index < 0) {
            return -1;
        }
        return MotionEventCompat.getY(ev, index);
    }

    private void setTargetOffsetTop(int offset, boolean requiresUpdate) {
//        mRefreshView.bringToFront();
        mTarget.offsetTopAndBottom(offset);
        mCurrentOffsetTop = mTarget.getTop();
        mRefreshDrawable.offsetTopAndBottom(offset);
        mLoadDrawable.offsetTopAndBottom(offset);
        if (requiresUpdate && android.os.Build.VERSION.SDK_INT < 11) {
            invalidate();
        }
    }

    private boolean canChildScrollUp() {
        if (android.os.Build.VERSION.SDK_INT < 14) {
            if (mTarget instanceof AbsListView) {
                final AbsListView absListView = (AbsListView) mTarget;
                return absListView.getChildCount() > 0
                        && (absListView.getFirstVisiblePosition() > 0 || absListView.getChildAt(0)
                        .getTop() < absListView.getPaddingTop());
            } else {
                return mTarget.getScrollY() > 0;
            }
        } else {
            return ViewCompat.canScrollVertically(mTarget, -1);
        }
    }

    public boolean canChildScrollDown() {
        if (android.os.Build.VERSION.SDK_INT < 14) {
            if (mTarget instanceof AbsListView) {
                final AbsListView absListView = (AbsListView) mTarget;
                View lastChild = absListView.getChildAt(absListView.getChildCount() - 1);
                if (lastChild != null) {
                    return (absListView.getLastVisiblePosition() == (absListView.getCount() - 1))
                            && lastChild.getBottom() > absListView.getPaddingBottom();
                } else {
                    return false;
                }
            } else {
                return mTarget.getHeight() - mTarget.getScrollY() > 0;
            }
        } else {
            return ViewCompat.canScrollVertically(mTarget, 1);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {

        ensureTarget();
        if (mTarget == null)
            return;

        int height = getMeasuredHeight();
        int width = getMeasuredWidth();
        int left = getPaddingLeft();
        int top = getPaddingTop();
        int right = getPaddingRight();
        int bottom = getPaddingBottom();

        mTarget.layout(left, top + mTarget.getTop(), left + width - right, top + height - bottom + mTarget.getTop());
        mRefreshView.layout(left, top, left + width - right, top + height - bottom);
        mLoadView.layout(left, top, left + width - right, top + height - bottom);
    }

    private int dp2px(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getContext().getResources().getDisplayMetrics());
    }

    public void setOnRefreshListener(OnRefreshListener listener) {
        mRefreshListener = listener;
    }

    public void setOnLoadListener(OnLoadListener listener) {
        mLoadLisener = listener;
    }

    public interface OnLoadListener{
        void onLoad();
    }

    public interface OnRefreshListener {
        void onRefresh();
    }
}