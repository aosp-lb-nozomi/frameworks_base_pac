/*
 * Copyright (C) 2010 The Android Open Source Project
 * This code has been modified.  Portions copyright (C) 2012, ParanoidAndroid Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar;

import java.util.ArrayList;

import android.app.ActivityManagerNative;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.res.Configuration;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.view.Display;
import android.view.IWindowManager;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.PopupMenu;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarIconList;
import com.android.internal.statusbar.StatusBarNotification;
import com.android.internal.widget.SizeAdaptiveLayout;
import com.android.systemui.SearchPanelView;
import com.android.systemui.SystemUI;
import com.android.systemui.recent.RecentsPanelView;
import com.android.systemui.recent.RecentTasksLoader;
import com.android.systemui.recent.TaskDescription;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationData.Entry;
import com.android.systemui.statusbar.policy.NotificationRowLayout;
import com.android.systemui.statusbar.tablet.StatusBarPanel;
import com.android.systemui.statusbar.WidgetView;

import com.android.systemui.R;

public abstract class BaseStatusBar extends SystemUI implements
    CommandQueue.Callbacks, RecentsPanelView.OnRecentsPanelVisibilityChangedListener {
    static final String TAG = "StatusBar";
    private static final boolean DEBUG = false;

    protected static final int MSG_OPEN_RECENTS_PANEL = 1020;
    protected static final int MSG_CLOSE_RECENTS_PANEL = 1021;
    protected static final int MSG_PRELOAD_RECENT_APPS = 1022;
    protected static final int MSG_CANCEL_PRELOAD_RECENT_APPS = 1023;
    protected static final int MSG_OPEN_SEARCH_PANEL = 1024;
    protected static final int MSG_CLOSE_SEARCH_PANEL = 1025;
    protected static final int MSG_SHOW_INTRUDER = 1026;
    protected static final int MSG_HIDE_INTRUDER = 1027;
    private boolean mTabletui;
    private boolean mLefty;

    private WidgetView mWidgetView;

    protected static final boolean ENABLE_INTRUDERS = false;

    // Should match the value in PhoneWindowManager
    public static final String SYSTEM_DIALOG_REASON_RECENT_APPS = "recentapps";

    public static final int EXPANDED_LEAVE_ALONE = -10000;
    public static final int EXPANDED_FULL_OPEN = -10001;

    protected CommandQueue mCommandQueue;
    protected IStatusBarService mBarService;
    protected H mHandler = createHandler();

    // all notifications
    protected NotificationData mNotificationData = new NotificationData();
    protected NotificationRowLayout mPile;

    protected StatusBarNotification mCurrentlyIntrudingNotification;

    // used to notify status bar for suppressing notification LED
    protected boolean mPanelSlightlyVisible;

    // Search panel
    protected SearchPanelView mSearchPanelView;

    // Recent apps
    protected RecentsPanelView mRecentsPanel;
    protected RecentTasksLoader mRecentTasksLoader;

    protected PopupMenu mNotificationBlamePopup;

    protected FrameLayout mStatusBarContainer;

    // storage
    private StorageManager mStorageManager;

    // UI-specific methods

    /**
     * Create all windows necessary for the status bar (including navigation, overlay panels, etc)
     * and add them to the window manager.
     */
    protected abstract void createAndAddWindows();

    public abstract void showClock(boolean show);

    protected Display mDisplay;
    private IWindowManager mWindowManager;
    private boolean mDeviceProvisioned = false;

    class StatusbarObserver extends ContentObserver {
        StatusbarObserver(Handler handler) {
            super(handler);
        }

/*        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUSBAR_CLOCK_STYLE), false, this);
//            resolver.registerContentObserver(Settings.System.getUriFor(
//                    Settings.System.STATUSBAR_CLOCK_STYLE), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            showClock(true);
        }
*/
    }

    public IWindowManager getWindowManager() {
        return mWindowManager;
    }

    public Display getDisplay() {
        return mDisplay;
    }

    public IStatusBarService getStatusBarService() {
        return mBarService;
    }

    protected boolean isDeviceProvisioned() {
        return mDeviceProvisioned;
    }

    private ContentObserver mProvisioningObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            final boolean provisioned = 0 != Settings.Secure.getInt(
                    mContext.getContentResolver(), Settings.Secure.DEVICE_PROVISIONED, 0);
            if (provisioned != mDeviceProvisioned) {
                mDeviceProvisioned = provisioned;
                updateNotificationIcons();
            }
        }
    };

    private RemoteViews.OnClickHandler mOnClickHandler = new RemoteViews.OnClickHandler() {
        @Override
        public boolean onClickHandler(View view, PendingIntent pendingIntent, Intent fillInIntent) {
            final boolean isActivity = pendingIntent.isActivity();
            if (isActivity) {
                try {
                    // The intent we are sending is for the application, which
                    // won't have permission to immediately start an activity after
                    // the user switches to home.  We know it is safe to do at this
                    // point, so make sure new activity switches are now allowed.
                    ActivityManagerNative.getDefault().resumeAppSwitches();
                    // Also, notifications can be launched from the lock screen,
                    // so dismiss the lock screen when the activity starts.
                    ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
                } catch (RemoteException e) {
                }
            }

            boolean handled = super.onClickHandler(view, pendingIntent, fillInIntent);

            if (isActivity && handled) {
                // close the shade if it was open
                animateCollapse(CommandQueue.FLAG_EXCLUDE_NONE);
                visibilityChanged(false);
            }
            return handled;
        }
    };

//    private boolean mShowNotificationCounts;

    public void start() {
        mDisplay = ((WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();

        mTabletui = Settings.System.getBoolean(mContext.getContentResolver(),
                        Settings.System.MODE_TABLET_UI, false);

        mLefty = (Settings.System.getBoolean(mContext.getContentResolver(),
                Settings.System.NAVIGATION_BAR_LEFTY_MODE, false));

//        StatusbarObserver StatusbarObserver = new StatusbarObserver(new Handler());
//        StatusbarObserver.observe();

        mProvisioningObserver.onChange(false); // set up
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DEVICE_PROVISIONED), true,
                mProvisioningObserver);

        // storage
        mStorageManager = (StorageManager) mContext.getSystemService(Context.STORAGE_SERVICE);
        mStorageManager.registerListener(
                new com.android.systemui.usb.StorageNotification(mContext));

        mWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService(Context.WINDOW_SERVICE));

        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        mStatusBarContainer = new FrameLayout(mContext);

/*        mShowNotificationCounts = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_NOTIF_COUNT, 0) == 1;
*/
        // Connect in to the status bar manager service
        StatusBarIconList iconList = new StatusBarIconList();
        ArrayList<IBinder> notificationKeys = new ArrayList<IBinder>();
        ArrayList<StatusBarNotification> notifications = new ArrayList<StatusBarNotification>();
        mCommandQueue = new CommandQueue(this, iconList);

        int[] switches = new int[7];
        ArrayList<IBinder> binders = new ArrayList<IBinder>();
        try {
            mBarService.registerStatusBar(mCommandQueue, iconList, notificationKeys, notifications,
                    switches, binders);
        } catch (RemoteException ex) {
            // If the system process isn't there we're doomed anyway.
        }

        createAndAddWindows();

        // create WidgetView
        WidgetView mWidgetView = new WidgetView(mContext,null);
        disable(switches[0]);
        setSystemUiVisibility(switches[1], 0xffffffff);
        topAppWindowChanged(switches[2] != 0);
        // StatusBarManagerService has a back up of IME token and it's restored here.
        setImeWindowStatus(binders.get(0), switches[3], switches[4]);
        setHardKeyboardStatus(switches[5] != 0, switches[6] != 0);

        // Set up the initial icon state
        int N = iconList.size();
        int viewIndex = 0;
        for (int i=0; i<N; i++) {
            StatusBarIcon icon = iconList.getIcon(i);
            if (icon != null) {
                addIcon(iconList.getSlot(i), i, viewIndex, icon);
                viewIndex++;
            }
        }

        // Set up the initial notification state
        N = notificationKeys.size();
        if (N == notifications.size()) {
            for (int i=0; i<N; i++) {
                addNotification(notificationKeys.get(i), notifications.get(i));
            }
        } else {
            Log.wtf(TAG, "Notification list length mismatch: keys=" + N
                    + " notifications=" + notifications.size());
        }

        if (DEBUG) {
            Slog.d(TAG, String.format(
                    "init: icons=%d disabled=0x%08x lights=0x%08x menu=0x%08x imeButton=0x%08x",
                   iconList.size(),
                   switches[0],
                   switches[1],
                   switches[2],
                   switches[3]
                   ));
        }
    }

    protected View updateNotificationVetoButton(View row, StatusBarNotification n) {
        View vetoButton = row.findViewById(R.id.veto);
        if (n.isClearable()) {
            final String _pkg = n.pkg;
            final String _tag = n.tag;
            final int _id = n.id;
            vetoButton.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        try {
                            mBarService.onNotificationClear(_pkg, _tag, _id);
                        } catch (RemoteException ex) {
                            // system process is dead if we're here.
                        }
                    }
                });
            vetoButton.setVisibility(View.VISIBLE);
        } else {
            vetoButton.setVisibility(View.GONE);
        }
        return vetoButton;
    }


    protected void applyLegacyRowBackground(StatusBarNotification sbn, View content) {
        if (sbn.notification.contentView.getLayoutId() !=
                com.android.internal.R.layout.notification_template_base) {
            int version = 0;
            try {
                ApplicationInfo info = mContext.getPackageManager().getApplicationInfo(sbn.pkg, 0);
                version = info.targetSdkVersion;
            } catch (NameNotFoundException ex) {
                Slog.e(TAG, "Failed looking up ApplicationInfo for " + sbn.pkg, ex);
            }
            if (version > 0 && version < Build.VERSION_CODES.GINGERBREAD) {
                content.setBackgroundResource(R.drawable.notification_row_legacy_bg);
            } else {
                content.setBackgroundResource(com.android.internal.R.drawable.notification_bg);
            }
        }
    }

    private void startApplicationDetailsActivity(String packageName) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }

    protected View.OnLongClickListener getNotificationLongClicker() {
        return new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                final String packageNameF = (String) v.getTag();
                if (packageNameF == null) return false;
                if (v.getWindowToken() == null) return false;
                mNotificationBlamePopup = new PopupMenu(mContext, v);
                mNotificationBlamePopup.getMenuInflater().inflate(
                        R.menu.notification_popup_menu,
                        mNotificationBlamePopup.getMenu());
                mNotificationBlamePopup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    public boolean onMenuItemClick(MenuItem item) {
                        if (item.getItemId() == R.id.notification_inspect_item) {
                            startApplicationDetailsActivity(packageNameF);
                            animateCollapse(CommandQueue.FLAG_EXCLUDE_NONE);
                        } else {
                            return false;
                        }
                        return true;
                    }
                });
                mNotificationBlamePopup.show();

                return true;
            }
        };
    }

    public void dismissPopups() {
        if (mNotificationBlamePopup != null) {
            mNotificationBlamePopup.dismiss();
            mNotificationBlamePopup = null;
        }
    }

    public void dismissIntruder() {
        // pass
    }

    @Override
    public void toggleRecentApps() {
        int msg = (mRecentsPanel.getVisibility() == View.VISIBLE)
            ? MSG_CLOSE_RECENTS_PANEL : MSG_OPEN_RECENTS_PANEL;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override
    public void preloadRecentApps() {
        int msg = MSG_PRELOAD_RECENT_APPS;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override
    public void cancelPreloadRecentApps() {
        int msg = MSG_CANCEL_PRELOAD_RECENT_APPS;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override
    public void showSearchPanel() {
        int msg = MSG_OPEN_SEARCH_PANEL;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override
    public void hideSearchPanel() {
        int msg = MSG_CLOSE_SEARCH_PANEL;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override
    public void onRecentsPanelVisibilityChanged(boolean visible) {
    }

    protected abstract WindowManager.LayoutParams getRecentsLayoutParams(
            LayoutParams layoutParams);

    protected abstract WindowManager.LayoutParams getSearchLayoutParams(
            LayoutParams layoutParams);

    protected void updateRecentsPanel(int recentsResId) {
        // Recents Panel
        boolean visible = false;
        ArrayList<TaskDescription> recentTasksList = null;
        boolean firstScreenful = false;
        if (mRecentsPanel != null) {
            visible = mRecentsPanel.isShowing();
            WindowManagerImpl.getDefault().removeView(mRecentsPanel);
            if (visible) {
                recentTasksList = mRecentsPanel.getRecentTasksList();
                firstScreenful = mRecentsPanel.getFirstScreenful();
            }
        }

        // Provide RecentsPanelView with a temporary parent to allow layout params to work.
        LinearLayout tmpRoot = new LinearLayout(mContext);
        mRecentsPanel = (RecentsPanelView) LayoutInflater.from(mContext).inflate(
                recentsResId, tmpRoot, false);
        mRecentsPanel.setRecentTasksLoader(mRecentTasksLoader);
        mRecentTasksLoader.setRecentsPanel(mRecentsPanel);
        mRecentsPanel.setOnTouchListener(
                 new TouchOutsideListener(MSG_CLOSE_RECENTS_PANEL, mRecentsPanel));
        mRecentsPanel.setVisibility(View.GONE);


        WindowManager.LayoutParams lp = getRecentsLayoutParams(mRecentsPanel.getLayoutParams());

        WindowManagerImpl.getDefault().addView(mRecentsPanel, lp);
        mRecentsPanel.setBar(this);
        if (visible) {
            mRecentsPanel.show(true, false, recentTasksList, firstScreenful);
        }

    }

    protected void updateSearchPanel() {
        // Search Panel
        boolean visible = false;
        if (mSearchPanelView != null) {
            visible = mSearchPanelView.isShowing();
            WindowManagerImpl.getDefault().removeView(mSearchPanelView);
        }

        // Provide SearchPanel with a temporary parent to allow layout params to work.
        LinearLayout tmpRoot = new LinearLayout(mContext);
        if (mTabletui) {
           mSearchPanelView = (SearchPanelView) LayoutInflater.from(mContext).inflate(
                        mLefty ? R.layout.status_bar_search_panel_lefty_tablet :
                            R.layout.status_bar_search_panel_tablet, tmpRoot, false);
        } else {
            // This is Phone or Phablet
           mSearchPanelView = (SearchPanelView) LayoutInflater.from(mContext).inflate(
                        mLefty ? R.layout.status_bar_search_panel_lefty :
                            R.layout.status_bar_search_panel, tmpRoot, false);
        }

        mSearchPanelView.setOnTouchListener(
                 new TouchOutsideListener(MSG_CLOSE_SEARCH_PANEL, mSearchPanelView));
        mSearchPanelView.setVisibility(View.GONE);

        WindowManager.LayoutParams lp = getSearchLayoutParams(mSearchPanelView.getLayoutParams());

        WindowManagerImpl.getDefault().addView(mSearchPanelView, lp);
        mSearchPanelView.setBar(this);
        if (visible) {
            mSearchPanelView.show(true, false);
        }
    }

    protected H createHandler() {
         return new H();
    }

    static void sendCloseSystemWindows(Context context, String reason) {
        if (ActivityManagerNative.isSystemReady()) {
            try {
                ActivityManagerNative.getDefault().closeSystemDialogs(reason);
            } catch (RemoteException e) {
            }
        }
    }

    protected class H extends Handler {
        public void handleMessage(Message m) {
            switch (m.what) {
             case MSG_OPEN_RECENTS_PANEL:
                  if (DEBUG) Slog.d(TAG, "opening recents panel");
                  if (mRecentsPanel != null) {
                      mRecentsPanel.show(true, false);
                  }
                  break;
             case MSG_CLOSE_RECENTS_PANEL:
                  if (DEBUG) Slog.d(TAG, "closing recents panel");
                  if (mRecentsPanel != null && mRecentsPanel.isShowing()) {
                      mRecentsPanel.show(false, false);
                  }
                  break;
             case MSG_PRELOAD_RECENT_APPS:
                  if (DEBUG) Slog.d(TAG, "preloading recents");
                  mRecentsPanel.preloadRecentTasksList();
                  break;
             case MSG_CANCEL_PRELOAD_RECENT_APPS:
                  if (DEBUG) Slog.d(TAG, "cancel preloading recents");
                  mRecentsPanel.clearRecentTasksList();
                  break;
             case MSG_OPEN_SEARCH_PANEL:
                 if (DEBUG) Slog.d(TAG, "opening search panel");
                 if (mSearchPanelView != null && mSearchPanelView.isAssistantAvailable()) {
                     mSearchPanelView.show(true, true);
                 }
                 break;
             case MSG_CLOSE_SEARCH_PANEL:
                 if (DEBUG) Slog.d(TAG, "closing search panel");
                 if (mSearchPanelView != null && mSearchPanelView.isShowing()) {
                     mSearchPanelView.show(false, true);
                 }
                 break;
            }
        }
    }

    public class TouchOutsideListener implements View.OnTouchListener {
        private int mMsg;
        private StatusBarPanel mPanel;

        public TouchOutsideListener(int msg, StatusBarPanel panel) {
            mMsg = msg;
            mPanel = panel;
        }

        public boolean onTouch(View v, MotionEvent ev) {
            final int action = ev.getAction();
            if (action == MotionEvent.ACTION_OUTSIDE
                || (action == MotionEvent.ACTION_DOWN
                    && !mPanel.isInContentArea((int)ev.getX(), (int)ev.getY()))) {
                mHandler.removeMessages(mMsg);
                mHandler.sendEmptyMessage(mMsg);
                return true;
            }
            return false;
        }
    }

    protected void workAroundBadLayerDrawableOpacity(View v) {
    }

    protected  boolean inflateViews(NotificationData.Entry entry, ViewGroup parent) {
        int rowHeight =
                mContext.getResources().getDimensionPixelSize(R.dimen.notification_row_min_height);
        int minHeight =
                mContext.getResources().getDimensionPixelSize(R.dimen.notification_min_height);
        int maxHeight =
                mContext.getResources().getDimensionPixelSize(R.dimen.notification_max_height);
        StatusBarNotification sbn = entry.notification;
        RemoteViews oneU = sbn.notification.contentView;
        RemoteViews large = sbn.notification.bigContentView;
        if (oneU == null) {
            return false;
        }

        // create the row view
        LayoutInflater inflater = (LayoutInflater)mContext.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        View row = inflater.inflate(R.layout.status_bar_notification_row, parent, false);

        // for blaming (see SwipeHelper.setLongPressListener)
        row.setTag(sbn.pkg);

        workAroundBadLayerDrawableOpacity(row);
        View vetoButton = updateNotificationVetoButton(row, sbn);
        vetoButton.setContentDescription(mContext.getString(
                R.string.accessibility_remove_notification));

        // NB: the large icon is now handled entirely by the template

        // bind the click event to the content area
        ViewGroup content = (ViewGroup)row.findViewById(R.id.content);
        ViewGroup adaptive = (ViewGroup)row.findViewById(R.id.adaptive);

        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        PendingIntent contentIntent = sbn.notification.contentIntent;
        if (contentIntent != null) {
            final View.OnClickListener listener = new NotificationClicker(contentIntent,
                    sbn.pkg, sbn.tag, sbn.id);
            content.setOnClickListener(listener);
        } else {
            content.setOnClickListener(null);
        }

        // TODO(cwren) normalize variable names with those in updateNotification
        View expandedOneU = null;
        View expandedLarge = null;
        Exception exception = null;
        try {
            expandedOneU = oneU.apply(mContext, adaptive, mOnClickHandler);
            if (large != null) {
                expandedLarge = large.apply(mContext, adaptive, mOnClickHandler);
            }
        }
        catch (RuntimeException e) {
            final String ident = sbn.pkg + "/0x" + Integer.toHexString(sbn.id);
            Slog.e(TAG, "couldn't inflate view for notification " + ident, e);
            return false;
        }

        if (expandedOneU != null) {
            SizeAdaptiveLayout.LayoutParams params =
                    new SizeAdaptiveLayout.LayoutParams(expandedOneU.getLayoutParams());
            params.minHeight = minHeight;
            params.maxHeight = minHeight;
            adaptive.addView(expandedOneU, params);
        }
        if (expandedLarge != null) {
            SizeAdaptiveLayout.LayoutParams params =
                    new SizeAdaptiveLayout.LayoutParams(expandedLarge.getLayoutParams());
            params.minHeight = minHeight+1;
            params.maxHeight = maxHeight;
            adaptive.addView(expandedLarge, params);
        }
        row.setDrawingCacheEnabled(true);

        applyLegacyRowBackground(sbn, content);

        row.setTag(R.id.expandable_tag, Boolean.valueOf(large != null));
        entry.row = row;
        entry.content = content;
        entry.expanded = expandedOneU;
        entry.setLargeView(expandedLarge);

        return true;
    }

    public NotificationClicker makeClicker(PendingIntent intent, String pkg, String tag, int id) {
        return new NotificationClicker(intent, pkg, tag, id);
    }

    private class NotificationClicker implements View.OnClickListener {
        private PendingIntent mIntent;
        private String mPkg;
        private String mTag;
        private int mId;

        NotificationClicker(PendingIntent intent, String pkg, String tag, int id) {
            mIntent = intent;
            mPkg = pkg;
            mTag = tag;
            mId = id;
        }

        public void onClick(View v) {
            try {
                // The intent we are sending is for the application, which
                // won't have permission to immediately start an activity after
                // the user switches to home.  We know it is safe to do at this
                // point, so make sure new activity switches are now allowed.
                ActivityManagerNative.getDefault().resumeAppSwitches();
                // Also, notifications can be launched from the lock screen,
                // so dismiss the lock screen when the activity starts.
                ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
            } catch (RemoteException e) {
            }

            if (mIntent != null) {
                int[] pos = new int[2];
                v.getLocationOnScreen(pos);
                Intent overlay = new Intent();
                overlay.setSourceBounds(
                        new Rect(pos[0], pos[1], pos[0]+v.getWidth(), pos[1]+v.getHeight()));
                try {
                    mIntent.send(mContext, 0, overlay);
                } catch (PendingIntent.CanceledException e) {
                    // the stack trace isn't very helpful here.  Just log the exception message.
                    Slog.w(TAG, "Sending contentIntent failed: " + e);
                }

                KeyguardManager kgm =
                    (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
                if (kgm != null) kgm.exitKeyguardSecurely(null);
            }

            try {
                mBarService.onNotificationClick(mPkg, mTag, mId);
            } catch (RemoteException ex) {
                // system process is dead if we're here.
            }

            // close the shade if it was open
            animateCollapse(CommandQueue.FLAG_EXCLUDE_NONE);
            visibilityChanged(false);

            // If this click was on the intruder alert, hide that instead
//            mHandler.sendEmptyMessage(MSG_HIDE_INTRUDER);
        }
    }
    /**
     * The LEDs are turned o)ff when the notification panel is shown, even just a little bit.
     * This was added last-minute and is inconsistent with the way the rest of the notifications
     * are handled, because the notification isn't really cancelled.  The lights are just
     * turned off.  If any other notifications happen, the lights will turn back on.  Steve says
     * this is what he wants. (see bug 1131461)
     */
    protected void visibilityChanged(boolean visible) {
        if (mPanelSlightlyVisible != visible) {
            mPanelSlightlyVisible = visible;
            try {
                mBarService.onPanelRevealed();
            } catch (RemoteException ex) {
                // Won't fail unless the world has ended.
            }
        }
    }

    /**
     * Cancel this notification and tell the StatusBarManagerService / NotificationManagerService
     * about the failure.
     *
     * WARNING: this will call back into us.  Don't hold any locks.
     */
    void handleNotificationError(IBinder key, StatusBarNotification n, String message) {
        removeNotification(key);
        try {
            mBarService.onNotificationError(n.pkg, n.tag, n.id, n.uid, n.initialPid, message);
        } catch (RemoteException ex) {
            // The end is nigh.
        }
    }

    protected StatusBarNotification removeNotificationViews(IBinder key) {
        NotificationData.Entry entry = mNotificationData.remove(key);
        if (entry == null) {
            Slog.w(TAG, "removeNotification for unknown key: " + key);
            return null;
        }
        // Remove the expanded view.
        ViewGroup rowParent = (ViewGroup)entry.row.getParent();
        if (rowParent != null) rowParent.removeView(entry.row);
        updateExpansionStates();
        updateNotificationIcons();

        return entry.notification;
    }

    protected StatusBarIconView addNotificationViews(IBinder key,
            StatusBarNotification notification) {
        if (DEBUG) {
            Slog.d(TAG, "addNotificationViews(key=" + key + ", notification=" + notification);
        }
        // Construct the icon.
        final StatusBarIconView iconView = new StatusBarIconView(mContext,
                notification.pkg + "/0x" + Integer.toHexString(notification.id),
                notification.notification);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        final StatusBarIcon ic = new StatusBarIcon(notification.pkg,
                    notification.notification.icon,
                    notification.notification.iconLevel,
                    notification.notification.number,
                    notification.notification.tickerText);
        if (!iconView.set(ic)) {
            handleNotificationError(key, notification, "Couldn't create icon: " + ic);
            return null;
        }
        // Construct the expanded view.
        NotificationData.Entry entry = new NotificationData.Entry(key, notification, iconView);
        if (!inflateViews(entry, mPile)) {
            handleNotificationError(key, notification, "Couldn't expand RemoteViews for: "
                    + notification);
            return null;
        }

        // Add the expanded view and icon.
        int pos = mNotificationData.add(entry);
        if (DEBUG) {
            Slog.d(TAG, "addNotificationViews: added at " + pos);
        }
        updateExpansionStates();
        updateNotificationIcons();

        return iconView;
    }

    protected boolean expandView(NotificationData.Entry entry, boolean expand) {
        int rowHeight =
                mContext.getResources().getDimensionPixelSize(R.dimen.notification_row_min_height);
        ViewGroup.LayoutParams lp = entry.row.getLayoutParams();
        if (entry.expandable() && expand) {
            if (DEBUG) Slog.d(TAG, "setting expanded row height to WRAP_CONTENT");
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        } else {
            if (DEBUG) Slog.d(TAG, "setting collapsed row height to " + rowHeight);
            lp.height = rowHeight;
        }
        entry.row.setLayoutParams(lp);
        return expand;
    }

    protected void updateExpansionStates() {
        int N = mNotificationData.size();
        for (int i = 0; i < N; i++) {
            NotificationData.Entry entry = mNotificationData.get(i);
            if (!entry.userLocked()) {
                if (i == (N-1)) {
                    if (DEBUG) Slog.d(TAG, "expanding top notification at " + i);
                    expandView(entry, true);
                } else {
                    if (!entry.userExpanded()) {
                        if (DEBUG) Slog.d(TAG, "collapsing notification at " + i);
                        expandView(entry, false);
                    } else {
                        if (DEBUG) Slog.d(TAG, "ignoring user-modified notification at " + i);
                    }
                }
            } else {
                if (DEBUG) Slog.d(TAG, "ignoring notification being held by user at " + i);
            }
        }
    }

    protected abstract void haltTicker();
    protected abstract void setAreThereNotifications();
    protected abstract void updateNotificationIcons();
    protected abstract void tick(IBinder key, StatusBarNotification n, boolean firstTime);
    protected abstract void updateExpandedViewPos(int expandedPosition);
    protected abstract int getExpandedViewMaxHeight();
    protected abstract boolean shouldDisableNavbarGestures();

    protected boolean isTopNotification(ViewGroup parent, NotificationData.Entry entry) {
        return parent != null && parent.indexOfChild(entry.row) == 0;
    }

    public void updateNotification(IBinder key, StatusBarNotification notification) {
        if (DEBUG) Slog.d(TAG, "updateNotification(" + key + " -> " + notification + ")");

        final NotificationData.Entry oldEntry = mNotificationData.findByKey(key);
        if (oldEntry == null) {
            Slog.w(TAG, "updateNotification for unknown key: " + key);
            return;
        }

        final StatusBarNotification oldNotification = oldEntry.notification;

        // XXX: modify when we do something more intelligent with the two content views
        final RemoteViews oldContentView = oldNotification.notification.contentView;
        final RemoteViews contentView = notification.notification.contentView;
        final RemoteViews oldBigContentView = oldNotification.notification.bigContentView;
        final RemoteViews bigContentView = notification.notification.bigContentView;

        if (DEBUG) {
            Slog.d(TAG, "old notification: when=" + oldNotification.notification.when
                    + " ongoing=" + oldNotification.isOngoing()
                    + " expanded=" + oldEntry.expanded
                    + " contentView=" + oldContentView
                    + " bigContentView=" + oldBigContentView
                    + " rowParent=" + oldEntry.row.getParent());
            Slog.d(TAG, "new notification: when=" + notification.notification.when
                    + " ongoing=" + oldNotification.isOngoing()
                    + " contentView=" + contentView
                    + " bigContentView=" + bigContentView);
        }

        // Can we just reapply the RemoteViews in place?  If when didn't change, the order
        // didn't change.

        // 1U is never null
        boolean contentsUnchanged = oldEntry.expanded != null
                && contentView.getPackage() != null
                && oldContentView.getPackage() != null
                && oldContentView.getPackage().equals(contentView.getPackage())
                && oldContentView.getLayoutId() == contentView.getLayoutId();
        // large view may be null
        boolean bigContentsUnchanged =
                (oldEntry.getLargeView() == null && bigContentView == null)
                || ((oldEntry.getLargeView() != null && bigContentView != null)
                    && bigContentView.getPackage() != null
                    && oldBigContentView.getPackage() != null
                    && oldBigContentView.getPackage().equals(bigContentView.getPackage())
                    && oldBigContentView.getLayoutId() == bigContentView.getLayoutId());
        ViewGroup rowParent = (ViewGroup) oldEntry.row.getParent();
        boolean orderUnchanged = notification.notification.when==oldNotification.notification.when
                && notification.score == oldNotification.score;
                // score now encompasses/supersedes isOngoing()

        boolean updateTicker = notification.notification.tickerText != null
                && !TextUtils.equals(notification.notification.tickerText,
                        oldEntry.notification.notification.tickerText);
        boolean isTopAnyway = isTopNotification(rowParent, oldEntry);
        if (contentsUnchanged && bigContentsUnchanged && (orderUnchanged || isTopAnyway)) {
            if (DEBUG) Slog.d(TAG, "reusing notification for key: " + key);
            oldEntry.notification = notification;
            try {
                // Reapply the RemoteViews
                contentView.reapply(mContext, oldEntry.expanded, mOnClickHandler);
                if (bigContentView != null && oldEntry.getLargeView() != null) {
                    bigContentView.reapply(mContext, oldEntry.getLargeView(), mOnClickHandler);
                }
                // update the contentIntent
                final PendingIntent contentIntent = notification.notification.contentIntent;
                if (contentIntent != null) {
                    final View.OnClickListener listener = makeClicker(contentIntent,
                            notification.pkg, notification.tag, notification.id);
                    oldEntry.content.setOnClickListener(listener);
                } else {
                    oldEntry.content.setOnClickListener(null);
                }
                // Update the icon.
                final StatusBarIcon ic = new StatusBarIcon(notification.pkg,
                        notification.notification.icon, notification.notification.iconLevel,
                        notification.notification.number,
                        notification.notification.tickerText);
                if (!oldEntry.icon.set(ic)) {
                    handleNotificationError(key, notification, "Couldn't update icon: " + ic);
                    return;
                }
                updateExpansionStates();
            }
            catch (RuntimeException e) {
                // It failed to add cleanly.  Log, and remove the view from the panel.
                Slog.w(TAG, "Couldn't reapply views for package " + contentView.getPackage(), e);
                removeNotificationViews(key);
                addNotificationViews(key, notification);
            }
        } else {
            if (DEBUG) Slog.d(TAG, "not reusing notification for key: " + key);
            if (DEBUG) Slog.d(TAG, "contents was " + (contentsUnchanged ? "unchanged" : "changed"));
            if (DEBUG) Slog.d(TAG, "order was " + (orderUnchanged ? "unchanged" : "changed"));
            if (DEBUG) Slog.d(TAG, "notification is " + (isTopAnyway ? "top" : "not top"));
            final boolean wasExpanded = oldEntry.userExpanded();
            removeNotificationViews(key);
            addNotificationViews(key, notification);
            if (wasExpanded) {
                final NotificationData.Entry newEntry = mNotificationData.findByKey(key);
                expandView(newEntry, true);
                newEntry.setUserExpanded(true);
            }
        }

        // Update the veto button accordingly (and as a result, whether this row is
        // swipe-dismissable)
        updateNotificationVetoButton(oldEntry.row, notification);

        // Restart the ticker if it's still running
        if (updateTicker) {
            haltTicker();
            tick(key, notification, false);
        }

        // Recalculate the position of the sliding windows and the titles.
        setAreThereNotifications();
        updateExpandedViewPos(EXPANDED_LEAVE_ALONE);

        // See if we need to update the intruder.
        if (ENABLE_INTRUDERS && oldNotification == mCurrentlyIntrudingNotification) {
            if (DEBUG) Slog.d(TAG, "updating the current intruder:" + notification);
            // XXX: this is a hack for Alarms. The real implementation will need to *update*
            // the intruder.
            if (notification.notification.fullScreenIntent == null) { // TODO(dsandler): consistent logic with add()
                if (DEBUG) Slog.d(TAG, "no longer intrudes!");
                mHandler.sendEmptyMessage(MSG_HIDE_INTRUDER);
            }
        }
    }

    // Q: What kinds of notifications should show during setup?
    // A: Almost none! Only things coming from the system (package is "android") that also
    // have special "kind" tags marking them as relevant for setup (see below).
    protected boolean showNotificationEvenIfUnprovisioned(StatusBarNotification sbn) {
        if ("android".equals(sbn.pkg)) {
            if (sbn.notification.kind != null) {
                for (String aKind : sbn.notification.kind) {
                    // IME switcher, created by InputMethodManagerService
                    if ("android.system.imeswitcher".equals(aKind)) return true;
                    // OTA availability & errors, created by SystemUpdateService
                    if ("android.system.update".equals(aKind)) return true;
                }
            }
        }
        return false;
    }

    public boolean inKeyguardRestrictedInputMode() {
        KeyguardManager km = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        return km.inKeyguardRestrictedInputMode();
    }

    public int screenLayout() {
        final int screenSize = Resources.getSystem().getConfiguration().screenLayout &
                Configuration.SCREENLAYOUT_SIZE_MASK;
        return screenSize;
    }

    public boolean isTablet() {
        return false;
    }
}
