package com.mintmedical.refresh;

/**
 * Created by SidHu on 2016/10/14.
 */

public enum RefreshMode {
    /**
     * Disable all Pull-to-Refresh gesture and Refreshing handling
    */
    DISABLED,

    /**
     * Only allow the user to Pull from the start of the Refreshable View to
     * refresh. The start is either the Top or Left, depending on the
     * scrolling direction.
     */
    PULL_FROM_START,

    /**
     * Only allow the user to Pull from the end of the Refreshable View to
     * refresh. The start is either the Bottom or Right, depending on the
     * scrolling direction.
     */
    PULL_FROM_END,

    /**
     * Allow the user to both Pull from the start, from the end to refresh.
     */
    BOTH;

    static RefreshMode getDefault() {
        return BOTH;
    }

    boolean permitsPullToRefresh() {
        return !(this == DISABLED);
    }
    boolean permitsPullFromStart() {
        return (this == RefreshMode.BOTH || this == RefreshMode.PULL_FROM_START);
    }
    boolean permitsPullFromEnd() {
        return (this == RefreshMode.BOTH || this == RefreshMode.PULL_FROM_END);
    }
}
