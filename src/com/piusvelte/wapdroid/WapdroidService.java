/*
 * Wapdroid - Android Location based Wifi Manager
 * Copyright (C) 2009 Bryan Emmanuel
 * 
 * This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.

 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *  
 *  Bryan Emmanuel piusvelte@gmail.com
 */

package com.piusvelte.wapdroid;

import java.util.List;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.CellLocation;
import android.telephony.NeighboringCellInfo;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.util.Log;

public class WapdroidService extends Service {
	private static int NOTIFY_ID = 1;
	public static final String WAKE_SERVICE = "com.piusvelte.wapdroid.WAKE_SERVICE";
	private WapdroidDbAdapter mDbHelper;
	private NotificationManager mNotificationManager;
	private TelephonyManager mTeleManager;
	private String mSSID, mBSSID;
	private List<NeighboringCellInfo> mNeighboringCells;
	private WifiManager mWifiManager;
	private int mCID = -1, mLAC = -1, mRSSI, mWifiState, mInterval, mNetworkType;
	private boolean mWifiIsEnabled, mNotify, mVibrate, mLed, mRingtone;
	private AlarmManager mAlarmManager;
	private PendingIntent mPendingIntent;
	private static final String TAG = "Wapdroid";
	
    private final IWapdroidService.Stub mWapdroidService = new IWapdroidService.Stub() {
		public void updatePreferences(int interval, boolean notify,
				boolean vibrate, boolean led, boolean ringtone)
				throws RemoteException {
			mInterval = interval;
			if (mNotify && !notify) {
				mNotificationManager.cancel(NOTIFY_ID);
				mNotificationManager = null;}
			else if (!mNotify && notify) {
				mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
				CharSequence contentTitle = getString(R.string.scanning);
			   	Notification notification = new Notification(R.drawable.scanning, contentTitle, System.currentTimeMillis());
				PendingIntent contentIntent = PendingIntent.getActivity(getBaseContext(), 0, new Intent(getBaseContext(), WapdroidService.class), 0);
			   	notification.setLatestEventInfo(getBaseContext(), contentTitle, getString(R.string.app_name), contentIntent);
				mNotificationManager.notify(NOTIFY_ID, notification);}
			mNotify = notify;
			mVibrate = vibrate;
			mLed = led;
			mRingtone = ringtone;}};
	
	private final PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
    	public void onCellLocationChanged(CellLocation location) {
    		getCellInfo(location);
    		if (ManageWakeLocks.hasLock()) {
    			if (mInterval > 0) mAlarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + mInterval, mPendingIntent);
    			ManageWakeLocks.release();}}
    	public void onSignalStrengthChanged(int asu) {
			mNetworkType = mTeleManager.getNetworkType();
			if ((mNetworkType == TelephonyManager.NETWORK_TYPE_GPRS) || (mNetworkType == TelephonyManager.NETWORK_TYPE_EDGE)) mRSSI = -113 + 2 * asu;
			else if ((mNetworkType == TelephonyManager.NETWORK_TYPE_UMTS) || (mNetworkType == TelephonyManager.NETWORK_TYPE_HSDPA) || (mNetworkType == TelephonyManager.NETWORK_TYPE_HSUPA) || (mNetworkType == TelephonyManager.NETWORK_TYPE_HSPA)) {
				Log.v(TAG, "UMTS");
				Log.v(TAG, "rssi: "+asu);}
    		checkSignalStrength();
    		if (ManageWakeLocks.hasLock()) {
    			if (mInterval > 0) mAlarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + mInterval, mPendingIntent);
    			ManageWakeLocks.release();}}};
	
	private BroadcastReceiver mReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
		    	mAlarmManager.cancel(mPendingIntent);
				ManageWakeLocks.release();
				context.startService(new Intent(context, WapdroidService.class));}
			else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
				if (mInterval > 0) mAlarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + mInterval, mPendingIntent);}
			else if (intent.getAction().equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
				// save the state as it's checked elsewhere, like the phonestatelistener
				int mWifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, 4);
				if (mWifiState == WifiManager.WIFI_STATE_ENABLED) {
					mWifiIsEnabled = true;
					wifiChanged();}
				else if (mWifiState != WifiManager.WIFI_STATE_UNKNOWN) {
					mWifiIsEnabled = false;
					mSSID = null;
					mBSSID = null;}}
			else if (intent.getAction().equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
				NetworkInfo mNetworkInfo = (NetworkInfo) intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
				if (mNetworkInfo.isConnected()) {
					setWifiInfo();
					wifiChanged();}
				else {
					mSSID = null;
					mBSSID = null;}}}};
	
	@Override
	public IBinder onBind(Intent intent) {
    	mAlarmManager.cancel(mPendingIntent);
		ManageWakeLocks.release();
		return mWapdroidService;}
	
	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		init();}
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		super.onStart(intent, startId);
		init();
		return START_STICKY;}
	
	private void init() {
		/*
		 * started on boot, wake, screen_on, ui, settings
		 * boot and wake will wakelock and should set the alarm,
		 * others should release the lock and cancel the alarm
		 */
		mWifiState = mWifiManager.getWifiState();
		mWifiIsEnabled = (mWifiState == WifiManager.WIFI_STATE_ENABLED);
		if (mWifiIsEnabled) setWifiInfo();
		if (mNotify) {
			CharSequence contentTitle = getString(R.string.scanning);
		   	Notification notification = new Notification(R.drawable.scanning, contentTitle, System.currentTimeMillis());
			PendingIntent contentIntent = PendingIntent.getActivity(getBaseContext(), 0, new Intent(getBaseContext(), WapdroidService.class), 0);
		   	notification.setLatestEventInfo(getBaseContext(), contentTitle, getString(R.string.app_name), contentIntent);
			mNotificationManager.notify(NOTIFY_ID, notification);}
		getCellInfo(mTeleManager.getCellLocation());
		if (mCID == -1) mTeleManager.listen(mPhoneStateListener, (PhoneStateListener.LISTEN_CELL_LOCATION | PhoneStateListener.LISTEN_SIGNAL_STRENGTH));
		else {
			if (ManageWakeLocks.hasLock()) {
				if (mInterval > 0) mAlarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + mInterval, mPendingIntent);
				ManageWakeLocks.release();}}}
	
    @Override
    public void onCreate() {
        super.onCreate();
		IntentFilter intentfilter = new IntentFilter();
		intentfilter.addAction(Intent.ACTION_SCREEN_OFF);
		intentfilter.addAction(Intent.ACTION_SCREEN_ON);
		intentfilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
		intentfilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
		registerReceiver(mReceiver, intentfilter);
		Intent i = new Intent(this, BootReceiver.class);
		i.setAction(WAKE_SERVICE);
		mPendingIntent = PendingIntent.getBroadcast(this, 0, i, 0);
		SharedPreferences prefs = (SharedPreferences) getSharedPreferences(getString(R.string.key_preferences), WapdroidService.MODE_PRIVATE);
		// initialize preferences, updated by UI
		mInterval = Integer.parseInt((String) prefs.getString(getString(R.string.key_interval), "0"));
		mNotify = prefs.getBoolean(getString(R.string.key_notify), false);
		if (mNotify) mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		mVibrate = prefs.getBoolean(getString(R.string.key_vibrate), false);
		mLed = prefs.getBoolean(getString(R.string.key_led), false);
		mRingtone = prefs.getBoolean(getString(R.string.key_ringtone), false);
		prefs = null;
		mDbHelper = new WapdroidDbAdapter(this);
		mTeleManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
		mWifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
		mAlarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);}
    
    @Override
    public void onDestroy() {
    	super.onDestroy();
    	if (mReceiver != null) {
    		unregisterReceiver(mReceiver);
    		mReceiver = null;}
		mTeleManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
		if (mNotify && (mNotificationManager != null)) mNotificationManager.cancel(NOTIFY_ID);}
    
    private void getCellInfo(CellLocation location) {
    		if (mTeleManager.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM) {
   				mCID = ((GsmCellLocation) location).getCid();
   				mLAC = ((GsmCellLocation) location).getLac();}
	       	else if (mTeleManager.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA) {
	    		// check the phone type, cdma is not available before API 2.0, so use a wrapper
	       		try {
	       			CdmaCellLocationWrapper cdma = new CdmaCellLocationWrapper(location);
	       			mCID = cdma.getBaseStationId();
	       			mLAC = cdma.getNetworkId();}
	       		catch (Throwable t) {
	       			mCID = -1;
	       			mLAC = -1;}}}
    
    private void checkSignalStrength() {
       	if ((mCID > 0) && (mDbHelper != null)) {
    		mDbHelper.open();
    		mNeighboringCells = mTeleManager.getNeighboringCellInfo();
			if (mWifiIsEnabled && (mSSID != null) && (mBSSID != null)) updateRange();
			else {
				boolean mInRange = false;
				if (mDbHelper.cellInRange(mCID, mLAC, mRSSI)) {
					mInRange = true;
					for (NeighboringCellInfo n : mNeighboringCells) {
						int cid = -1, lac = -1, rssi = 99;
		    			if ((mNetworkType == TelephonyManager.NETWORK_TYPE_GPRS) || (mNetworkType == TelephonyManager.NETWORK_TYPE_EDGE)) {
		    				cid = n.getCid();
		    				lac = n.getLac();
		    				rssi = n.getRssi();}
		    			else if ((mNetworkType == TelephonyManager.NETWORK_TYPE_UMTS) || (mNetworkType == TelephonyManager.NETWORK_TYPE_HSDPA) || (mNetworkType == TelephonyManager.NETWORK_TYPE_HSUPA) || (mNetworkType == TelephonyManager.NETWORK_TYPE_HSPA)) {
		    				Log.v(TAG, "UMTS");
		    				Log.v(TAG, "cid: "+n.getCid());
		    				Log.v(TAG, "lac: "+n.getLac());
		    				Log.v(TAG, "rssi: "+n.getRssi());
		    				Log.v(TAG, "psc: "+n.getPsc());}
						if (mInRange && (cid > 0)) mInRange = mDbHelper.cellInRange(cid, lac, rssi);}}
				if ((mInRange && !mWifiIsEnabled && (mWifiState != WifiManager.WIFI_STATE_ENABLING)) || (!mInRange && mWifiIsEnabled)) {
					mWifiManager.setWifiEnabled(mInRange);
					if (mNotify) {
						CharSequence contentTitle = getString(R.string.label_WIFI) + " " + getString(mInRange ? R.string.label_enabled : R.string.label_disabled);
					   	Notification notification = new Notification((mInRange ? R.drawable.statuson : R.drawable.status), contentTitle, System.currentTimeMillis());
					   	Intent i = new Intent(getBaseContext(), WapdroidService.class);
						PendingIntent contentIntent = PendingIntent.getActivity(getBaseContext(), 0, i, 0);
					   	notification.setLatestEventInfo(getBaseContext(), contentTitle, getString(R.string.app_name), contentIntent);
					   	if (mVibrate) notification.defaults |= Notification.DEFAULT_VIBRATE;
					   	if (mLed) notification.defaults |= Notification.DEFAULT_LIGHTS;
					   	if (mRingtone) notification.defaults |= Notification.DEFAULT_SOUND;
						mNotificationManager.notify(NOTIFY_ID, notification);}}}
	    	mDbHelper.close();}}
    
    private void setWifiInfo() {
		mSSID = mWifiManager.getConnectionInfo().getSSID();
		mBSSID = mWifiManager.getConnectionInfo().getBSSID();}
    
    private void updateRange() {
    	int network = mDbHelper.updateNetworkRange(mSSID, mBSSID, mCID, mLAC, mRSSI);
		for (NeighboringCellInfo n : mNeighboringCells) {
			int cid = n.getCid();
			int lac = n.getLac();
			int rssi = n.getRssi();
			if (cid > 0) mDbHelper.createPair(cid, lac, network, rssi);}}
	
	private void wifiChanged() {
		if (mWifiIsEnabled && (mSSID != null) && (mBSSID != null) && (mCID > 0) && (mDbHelper != null)) {
			mDbHelper.open();
	    	updateRange();
	    	mDbHelper.close();}}}