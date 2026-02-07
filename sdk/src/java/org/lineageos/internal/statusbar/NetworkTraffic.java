/**
 * Copyright (C) 2017-2020 The LineageOS project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.internal.statusbar;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkStats;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

import lineageos.providers.LineageSettings;

import org.lineageos.platform.internal.R;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public class NetworkTraffic extends TextView {
    private static final String TAG = "NetworkTraffic";

    private static final boolean DEBUG = false;

    private static final int MODE_DISABLED = 0;
    private static final int MODE_UPSTREAM_ONLY = 1;
    private static final int MODE_DOWNSTREAM_ONLY = 2;
    private static final int MODE_UPSTREAM_AND_DOWNSTREAM = 3;

    private static final int MESSAGE_TYPE_PERIODIC_REFRESH = 0;
    private static final int MESSAGE_TYPE_UPDATE_VIEW = 1;
    private static final int MESSAGE_TYPE_ADD_NETWORK = 2;
    private static final int MESSAGE_TYPE_REMOVE_NETWORK = 3;

    private static final int REFRESH_INTERVAL = 2000;
    private static final float REFRESH_INTERVAL_JITTER = 0.95f;

    private static final int UNITS_KILOBITS = 0;
    private static final int UNITS_MEGABITS = 1;
    private static final int UNITS_KILOBYTES = 2;
    private static final int UNITS_MEGABYTES = 3;

    private static final long AUTOHIDE_THRESHOLD_KILOBITS  = 10;
    private static final long AUTOHIDE_THRESHOLD_MEGABITS  = 100;
    private static final long AUTOHIDE_THRESHOLD_KILOBYTES = 8;
    private static final long AUTOHIDE_THRESHOLD_MEGABYTES = 80;

    private static final float BITS_PER_BYTE = 8f;
    private static final float KILO = 1000f;
    private static final long SPEED_THRESHOLD_KBPS = 10;
    private static final float VALUE_SIZE_SPAN = 1.3f;
    private static final float UNIT_SIZE_SPAN = 1.1f;

    private int mMode = MODE_DISABLED;
    private boolean mNetworkTrafficIsVisible;
    private long mTxKbps;
    private long mRxKbps;
    private long mLastTxBytes;
    private long mLastRxBytes;
    private long mLastUpdateTime;
    private int mTextSizeSingle;
    private int mTextSizeMulti;
    private int mArrowPadding;
    private boolean mAutoHide;
    private long mAutoHideThreshold;
    private int mUnits;
    private boolean mShowUnits;
    private boolean mLayoutHorizontal;
    private boolean mShowArrow;
    private int mDarkModeFillColor;
    private int mLightModeFillColor;
    private int mIconTint = Color.WHITE;
    private SettingsObserver mObserver;
    private Drawable mDrawable;

    private boolean mIsConnected = false;

    private final ConnectivityManager mConnectivityManager;
    private final ConcurrentHashMap<Network, LinkProperties> mLinkPropertiesMap =
            new ConcurrentHashMap<>();
    private volatile boolean mNetworksChanged = true;

    private INetworkManagementService mNetworkManagementService;

    public NetworkTraffic(Context context) {
        this(context, null);
    }

    public NetworkTraffic(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NetworkTraffic(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mNetworkManagementService = INetworkManagementService.Stub.asInterface(
                    ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE));

        final Resources resources = getResources();
        mTextSizeSingle = resources.getDimensionPixelSize(R.dimen.net_traffic_single_text_size);
        mTextSizeMulti = resources.getDimensionPixelSize(R.dimen.net_traffic_multi_text_size);

        DisplayMetrics metrics = resources.getDisplayMetrics();
        mArrowPadding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, metrics);

        mNetworkTrafficIsVisible = false;

        mObserver = new SettingsObserver(mTrafficHandler);

        mConnectivityManager = getContext().getSystemService(ConnectivityManager.class);
        final NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        mConnectivityManager.registerNetworkCallback(request, mNetworkCallback);
        mConnectivityManager.registerDefaultNetworkCallback(mDefaultNetworkCallback);

        setGravity(Gravity.CENTER);
        setLineSpacing(0, 0.80f);
    }

    private LineageStatusBarItem.DarkReceiver mDarkReceiver =
            new LineageStatusBarItem.DarkReceiver() {
        public void onDarkChanged(Rect area, float darkIntensity, int tint) {
            mIconTint = tint;
            setTextColor(mIconTint);
            updateTrafficDrawableColor();
        }
        public void setFillColors(int darkColor, int lightColor) {
            mDarkModeFillColor = darkColor;
            mLightModeFillColor = lightColor;
        }
    };

    private LineageStatusBarItem.VisibilityReceiver mVisibilityReceiver =
            new LineageStatusBarItem.VisibilityReceiver() {
        public void onVisibilityChanged(boolean isVisible) {
            if (mNetworkTrafficIsVisible != isVisible) {
                mNetworkTrafficIsVisible = isVisible;
                updateViewState();
            }
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        LineageStatusBarItem.Manager manager =
                LineageStatusBarItem.findManager((View) this);
        manager.addDarkReceiver(mDarkReceiver);
        manager.addVisibilityReceiver(mVisibilityReceiver);

        mObserver.observe();
        updateSettings();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mObserver.unobserve();
        mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
        mConnectivityManager.unregisterNetworkCallback(mDefaultNetworkCallback);
        mTrafficHandler.removeCallbacksAndMessages(null);
    }

    private static class TrafficHandler extends Handler {
        private final WeakReference<NetworkTraffic> mOuter;

        TrafficHandler(NetworkTraffic outer) {
            super(Looper.getMainLooper());
            mOuter = new WeakReference<>(outer);
        }

        @Override
        public void handleMessage(Message msg) {
            NetworkTraffic traffic = mOuter.get();
            if (traffic == null) {
                return;
            }
            switch (msg.what) {
                case MESSAGE_TYPE_PERIODIC_REFRESH:
                    traffic.recalculateStats();
                    traffic.displayStatsAndReschedule();
                    break;

                case MESSAGE_TYPE_UPDATE_VIEW:
                    traffic.displayStatsAndReschedule();
                    break;

                case MESSAGE_TYPE_ADD_NETWORK:
                    final LinkPropertiesHolder lph = (LinkPropertiesHolder) msg.obj;
                    traffic.mLinkPropertiesMap.put(lph.getNetwork(), lph.getLinkProperties());
                    traffic.mNetworksChanged = true;
                    break;

                case MESSAGE_TYPE_REMOVE_NETWORK:
                    traffic.mLinkPropertiesMap.remove((Network) msg.obj);
                    traffic.mNetworksChanged = true;
                    break;
            }
        }
    }

    private final TrafficHandler mTrafficHandler = new TrafficHandler(this);

    private void recalculateStats() {
        final long now = SystemClock.elapsedRealtime();
        final long timeDelta = now - mLastUpdateTime; /* ms */
        if (timeDelta < REFRESH_INTERVAL * REFRESH_INTERVAL_JITTER) {
            return;
        }
        long txBytes = 0;
        long rxBytes = 0;
        for (LinkProperties linkProperties : mLinkPropertiesMap.values()) {
            for (String iface : linkProperties.getAllInterfaceNames()) {
                if (iface == null) {
                    continue;
                }
                final long ifaceTxBytes = TrafficStats.getTxBytes(iface);
                final long ifaceRxBytes = TrafficStats.getRxBytes(iface);
                if (DEBUG) {
                    Log.d(TAG, "adding stats from interface " + iface
                            + " txbytes " + ifaceTxBytes + " rxbytes " + ifaceRxBytes);
                }
                txBytes += ifaceTxBytes;
                rxBytes += ifaceRxBytes;
            }
        }

        final TetheringStats tetheringStats = getOffloadTetheringStats();
        txBytes += tetheringStats.txBytes;
        rxBytes += tetheringStats.rxBytes;

        if (DEBUG) {
            Log.d(TAG, "mNetworksChanged = " + mNetworksChanged);
            Log.d(TAG, "tether hw offload txBytes: " + tetheringStats.txBytes
                    + " rxBytes: " + tetheringStats.rxBytes);
        }

        final long txBytesDelta = txBytes - mLastTxBytes;
        final long rxBytesDelta = rxBytes - mLastRxBytes;

        if (!mNetworksChanged && timeDelta > 0 && txBytesDelta >= 0 && rxBytesDelta >= 0) {
            mTxKbps = (long) (txBytesDelta * BITS_PER_BYTE / KILO / (timeDelta / KILO));
            mRxKbps = (long) (rxBytesDelta * BITS_PER_BYTE / KILO / (timeDelta / KILO));
        } else if (mNetworksChanged) {
            mTxKbps = 0;
            mRxKbps = 0;
            mNetworksChanged = false;
        }
        mLastTxBytes = txBytes;
        mLastRxBytes = rxBytes;
        mLastUpdateTime = now;
    }

    private void displayStatsAndReschedule() {
        final boolean enabled = mMode != MODE_DISABLED && mIsConnected;

        long speedToShow = 0;
        if (mMode == MODE_UPSTREAM_ONLY) {
            speedToShow = mTxKbps;
        } else if (mMode == MODE_DOWNSTREAM_ONLY) {
            speedToShow = mRxKbps;
        } else {
            speedToShow = mTxKbps + mRxKbps;
        }

        boolean shouldHide = false;
        if (mAutoHide) {
             shouldHide = speedToShow < mAutoHideThreshold;
        }

        if (!enabled || shouldHide) {
            setText("");
            setVisibility(GONE);
        } else {
            CharSequence output = formatOutput(speedToShow);

            if (!output.toString().contentEquals(getText())) {
                setText(output);

                if (mLayoutHorizontal || !mShowUnits) {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, (float) mTextSizeSingle);
                } else {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, (float) mTextSizeMulti);
                }
            }

            updateTrafficDrawable();
            setVisibility(VISIBLE);
        }

        mTrafficHandler.removeMessages(MESSAGE_TYPE_PERIODIC_REFRESH);
        if (enabled && mNetworkTrafficIsVisible) {
            mTrafficHandler.sendEmptyMessageDelayed(MESSAGE_TYPE_PERIODIC_REFRESH,
                    REFRESH_INTERVAL);
        }
    }

    private CharSequence formatOutput(long speedKbps) {
        float value;
        String unit;
        String formatString;

        switch (mUnits) {
            case UNITS_KILOBITS:
                value = (float) speedKbps;
                unit = mContext.getString(R.string.kilobitspersecond_short);
                formatString = "%.0f";
                break;
            case UNITS_MEGABITS:
                value = (float) speedKbps / KILO;
                unit = mContext.getString(R.string.megabitspersecond_short);
                formatString = "%.2f";
                break;
            case UNITS_KILOBYTES:
                value = (float) speedKbps / BITS_PER_BYTE;
                unit = mContext.getString(R.string.kilobytespersecond_short);
                formatString = "%.0f";
                break;
            case UNITS_MEGABYTES:
                value = (float) speedKbps / (BITS_PER_BYTE * KILO);
                unit = mContext.getString(R.string.megabytespersecond_short);
                formatString = "%.2f";
                break;
            default:
                value = 0;
                unit = "?";
                formatString = "%.2f";
                break;
        }

        String valueStr = String.format(Locale.US, formatString, value);

        if (!mShowUnits) {
            SpannableString spannable = new SpannableString(valueStr);
            if (mLayoutHorizontal) {
                spannable.setSpan(new TypefaceSpan("sans-serif-medium"), 0, valueStr.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                spannable.setSpan(new StyleSpan(Typeface.BOLD), 0, valueStr.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return spannable;
        }

        String separator = mLayoutHorizontal ? "" : "\n";
        String fullText = valueStr + separator + unit;

        SpannableString spannable = new SpannableString(fullText);
        int splitIndex = valueStr.length();

        if (mLayoutHorizontal) {
            spannable.setSpan(new TypefaceSpan("sans-serif-medium"), 0, fullText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            spannable.setSpan(new StyleSpan(Typeface.BOLD), 0, fullText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            spannable.setSpan(new RelativeSizeSpan(VALUE_SIZE_SPAN), 0, splitIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            spannable.setSpan(new RelativeSizeSpan(UNIT_SIZE_SPAN), splitIndex + 1, fullText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return spannable;
    }

    private void updateTrafficDrawable() {
        if (!mShowArrow) {
            setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
            setPaddingRelative(0, 0, mArrowPadding, 0);
            return;
        }

        setPaddingRelative(0, 0, 0, 0);

        int drawableResId;
        if (mMode == MODE_UPSTREAM_ONLY) {
            drawableResId = R.drawable.stat_sys_network_traffic_up;
        } else if (mMode == MODE_DOWNSTREAM_ONLY) {
            drawableResId = R.drawable.stat_sys_network_traffic_down;
        } else {
            long totalSpeed = mTxKbps + mRxKbps;
            if (totalSpeed <= SPEED_THRESHOLD_KBPS) {
                 drawableResId = R.drawable.stat_sys_network_traffic_updown;
            } else if (mTxKbps > (mRxKbps + SPEED_THRESHOLD_KBPS)) {
                drawableResId = R.drawable.stat_sys_network_traffic_up;
            } else if (mRxKbps > (mTxKbps + SPEED_THRESHOLD_KBPS)) {
                drawableResId = R.drawable.stat_sys_network_traffic_down;
            } else {
                drawableResId = R.drawable.stat_sys_network_traffic_updown;
            }
        }

        Drawable d = ResourcesCompat.getDrawable(getResources(), drawableResId, getContext().getTheme());
        if (d != null) {
            d.setColorFilter(mIconTint, PorterDuff.Mode.MULTIPLY);
            setCompoundDrawablesWithIntrinsicBounds(null, null, d, null);
            mDrawable = d;
        }
    }

    private final ConnectivityManager.NetworkCallback mDefaultNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            mIsConnected = true;
            updateViewState();
        }

        @Override
        public void onLost(Network network) {
            mIsConnected = false;
            updateViewState();
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            updateViewState();
        }
    };

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.NETWORK_TRAFFIC_MODE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.NETWORK_TRAFFIC_AUTOHIDE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.NETWORK_TRAFFIC_UNITS),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.NETWORK_TRAFFIC_SHOW_UNITS),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.NETWORK_TRAFFIC_LAYOUT),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.NETWORK_TRAFFIC_SHOW_ARROW),
                    false, this, UserHandle.USER_ALL);
        }

        void unobserve() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    private class TetheringStats {
        long txBytes;
        long rxBytes;
    }

    private TetheringStats getOffloadTetheringStats() {
        TetheringStats tetheringStats = new TetheringStats();

        NetworkStats stats = null;
        try {
            // STATS_PER_UID returns hw offload and netd stats combined (as entry UID_TETHERING)
            // STATS_PER_IFACE returns only hw offload stats (as entry UID_ALL)
            stats = mNetworkManagementService.getNetworkStatsTethering(
                    NetworkStats.STATS_PER_IFACE);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to call getNetworkStatsTethering: " + e);
        }
        if (stats == null) {
            // nothing we can do except return zero stats
            return tetheringStats;
        }

        NetworkStats.Entry entry = null;
        // Entries here are per tethered interface.
        // Counters persist even after tethering has been disabled.
        for (int i = 0; i < stats.size(); i++) {
            entry = stats.getValues(i, entry);
            if (DEBUG) {
                Log.d(TAG, "tethering stats entry: " + entry);
            }
            // hw offload tether stats are reported under UID_ALL.
            if (entry.uid == NetworkStats.UID_ALL) {
                tetheringStats.txBytes += entry.txBytes;
                tetheringStats.rxBytes += entry.rxBytes;
            }
        }
        return tetheringStats;
    }

    private void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        mMode = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_MODE, 0, UserHandle.USER_CURRENT);
        mAutoHide = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_AUTOHIDE, 0, UserHandle.USER_CURRENT) == 1;
        mUnits = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_UNITS, /* Mbps */ 1,
                UserHandle.USER_CURRENT);
        mShowUnits = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_SHOW_UNITS, 1,
                UserHandle.USER_CURRENT) == 1;

        mLayoutHorizontal = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_LAYOUT, 0,
                UserHandle.USER_CURRENT) == 1;
        mShowArrow = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_SHOW_ARROW, 1,
                UserHandle.USER_CURRENT) == 1;

        switch (mUnits) {
            case UNITS_KILOBITS:
                mAutoHideThreshold = AUTOHIDE_THRESHOLD_KILOBITS;
                break;
            case UNITS_MEGABITS:
                mAutoHideThreshold = AUTOHIDE_THRESHOLD_MEGABITS;
                break;
            case UNITS_KILOBYTES:
                mAutoHideThreshold = AUTOHIDE_THRESHOLD_KILOBYTES;
                break;
            case UNITS_MEGABYTES:
                mAutoHideThreshold = AUTOHIDE_THRESHOLD_MEGABYTES;
                break;
            default:
                mAutoHideThreshold = 0;
                break;
        }

        if (mMode != MODE_DISABLED) {
            updateTrafficDrawableColor();
        }
        updateViewState();
    }

    private void updateViewState() {
        mTrafficHandler.sendEmptyMessage(MESSAGE_TYPE_UPDATE_VIEW);
    }

    private void updateTrafficDrawableColor() {
        if (mDrawable != null) {
            mDrawable.setColorFilter(mIconTint, PorterDuff.Mode.MULTIPLY);
        }
    }

    private final ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
            Message msg = new Message();
            msg.what = MESSAGE_TYPE_ADD_NETWORK;
            msg.obj = new LinkPropertiesHolder(network, linkProperties);
            mTrafficHandler.sendMessage(msg);
        }

        @Override
        public void onLost(Network network) {
            Message msg = new Message();
            msg.what = MESSAGE_TYPE_REMOVE_NETWORK;
            msg.obj = network;
            mTrafficHandler.sendMessage(msg);
        }
    };

    private static class LinkPropertiesHolder {
        private final Network mNetwork;
        private final LinkProperties mLinkProperties;

        public LinkPropertiesHolder(Network network, LinkProperties linkProperties) {
            mNetwork = network;
            mLinkProperties = linkProperties;
        }

        public Network getNetwork() {
            return mNetwork;
        }

        public LinkProperties getLinkProperties() {
            return mLinkProperties;
        }
    }
}
