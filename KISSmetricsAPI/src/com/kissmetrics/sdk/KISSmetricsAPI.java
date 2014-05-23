//
// KISSmetricsSDK
//
// Copyright 2014 KISSmetrics
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


package com.kissmetrics.sdk;

import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * KISSmetricsAPI
 * 
 * Public API for sending identities, events and properties to KISSmetrics from
 * Android applications. Compatible with Android 2.1+
 * 
 */
public final class KISSmetricsAPI implements VerificationDelegate {

	private static final long FAILSAFE_MAX_VERIFICATION_DUR = 1209600000L; // 14 days
	
	private static KISSmetricsAPI sharedAPI = null;
	private static ConnectionImpl connectionImpl = null;
	private static VerificationImpl verificationImpl = null;
	private static ExecutorService dataExecutor = Executors.newFixedThreadPool(2);
	
	private TrackingRunnables trackingRunnables = null;
	private Sender sender;
	private String key;
	private Context context;

	/**
	 * Allows for injection of a mock Connection.
	 * 
	 * @param connImpl
	 *            A mock Connection to use under test.
	 */
	protected static void setConnectionImpl(ConnectionImpl connImpl) {
		connectionImpl = connImpl;
	}

	/**
	 * Allows for injection of a mock Verification.
	 * 
	 * @param verImpl
	 *            A mock Verification to use under test.
	 */
	protected static void setVerificationImpl(VerificationImpl verImpl) {
		verificationImpl = verImpl;
	}

	/**
	 * Initializes the default Connection if not set. Allows for injection of
	 * mock HttpURLConnection within ConnectionImpl via method override.
	 * 
	 * @return Connection
	 */
	protected static ConnectionImpl connection() {
		if (connectionImpl == null) {
			connectionImpl = new ConnectionImpl();
		}
		return connectionImpl;
	}

	/**
	 * Initializes the default Connection if not set. Allows for injection of
	 * mock HttpURLConnection within VerificationImpl via method override.
	 * 
	 * Returns the injected verificationImpl if it exists.
	 * 
	 * @return Connection
	 */
	protected static VerificationImpl verificationImpl() {
		if (verificationImpl != null) {
			return verificationImpl;
		}
		return new VerificationImpl();
	}

	/**
	 * Initializes the private singleton.
	 * 
	 * @param productKey
	 *            KISSmetrics product key.
	 * @param context
	 *            Android application context.
	 */
	private KISSmetricsAPI(final String productKey, final Context appContext) {

		key = productKey;
		context = appContext;

		ArchiverImpl.sharedArchiver(this.key, this.context);

		// Ensure an Install UUID exists
		String installUuid = ArchiverImpl.sharedArchiver().getInstallUuid();

		if (installUuid == null || installUuid.length() == 0) {
			// No install id has been set. Make and archive a new one.
			ArchiverImpl.sharedArchiver()
					.archiveInstallUuid(generateID());
			installUuid = ArchiverImpl.sharedArchiver().getInstallUuid();
		}

		// Ensure an Identity exists
		String archIdentity = ArchiverImpl.sharedArchiver().getIdentity();

		if (archIdentity == null || archIdentity.length() == 0) {
			// No identity has been set. Make and archive a new one.
			ArchiverImpl.sharedArchiver().archiveFirstIdentity(
					generateID());
		}

		// Initialize Sender using archived doSend boolean to set disabled/ready state.
		sender = new Sender(!ArchiverImpl.sharedArchiver().getDoSend());
		
		// Set the TrackingRunnables state
		if (ArchiverImpl.sharedArchiver().getDoTrack()) {
			trackingRunnables = new TrackingRunnablesTrackingState();
		} else {
			trackingRunnables = new TrackingRunnablesNonTrackingState();
		}
	}

	/**
	 * Initializes and/or returns the KISSmetricsAPI singleton instance. This
	 * method must be called before making any other calls.
	 * 
	 * @param productKey
	 *            KISSmetrics product key.
	 * @param applicationContext
	 *            Android application context.
	 * @return KISSmetricsAPI singleton instance.
	 */
	public static synchronized KISSmetricsAPI sharedAPI(
			final String productKey, final Context applicationContext) {
		if (sharedAPI == null) {
			sharedAPI = new KISSmetricsAPI(productKey, applicationContext);
		}

		// Verifying tracking here will allow for checks from the Android app's
		// state has been cached with the SDK already initialized.
		sharedAPI.verifyForTracking();

		return sharedAPI;
	}

	/**
	 * @return KISSmetricsAPI singleton instance.
	 */
	public static synchronized KISSmetricsAPI sharedAPI() {
		if (sharedAPI == null) {
			Log.w("KISSmetricsAPI",
					"KISSMetricsAPI: WARNING - Returning null object in sharedAPI as "
							+ "sharedAPI(<API_KEY>, <Context>): has not been called.");
		}
		return sharedAPI;
	}

	
	/************************************************
	 * Private methods
	 ************************************************/

	/**
	 * Creates a random UUID string.
	 * 
	 * @return UUID string
	 */
	private String generateID() {
		return UUID.randomUUID().toString();
	}

	/**
	 * Verifies the KISSmetricsAPI product for tracking asynchronously.
	 */
	private void verifyForTracking() {

		if (System.currentTimeMillis() < ArchiverImpl.sharedArchiver()
				.getVerificationExpDate()) {
			return;
		}

		new Thread(new Runnable() {
			public void run() {
				String installUuid = ArchiverImpl.sharedArchiver()
						.getInstallUuid();
				VerificationImpl verification = verificationImpl();
				verification.verifyTracking(key, installUuid, sharedAPI());
			}
		}).start();
	}

	
	/**
	 * Starts sending to empty the sendQueue when sender is in the 
	 * ready state.
	 */
	protected void sendRecords() {
		sender.startSending();
	}
	
	/************************************************
	 * Public methods
	 ************************************************/

	/**
	 * Applies an identity to the user.
	 * 
	 * @param identity
	 *            user identity
	 */
	public void identify(final String identity) {
		// Pass this call onto the mDataExecutor ExecutorService
		// as a Runnable object to be run on a background thread.
		dataExecutor.execute(trackingRunnables.identify(identity,
				ArchiverImpl.sharedArchiver(), this));
	}

	/**
	 * This getter does not lock on the Archiver instance to prevent locking of
	 * an application's main thread. All writes to identity lock on the
	 * singleton instance of sharedArchiver.
	 * 
	 * @return Last provided identity string for the current user
	 */
	public String identity() {
		return ArchiverImpl.sharedArchiver().getIdentity();
	}

	/**
	 * Applies an alias to an identity
	 * 
	 * @param alias
	 * @param identity
	 */
	public void alias(final String alias, final String identity) {
		dataExecutor.execute(trackingRunnables.alias(alias, identity,
				ArchiverImpl.sharedArchiver(), this));
	}

	/**
	 * Sets a new random identity that isn't aliased or associated with a
	 * previous identity.
	 */
	public void clearIdentity() {
		dataExecutor.execute(trackingRunnables.clearIdentity(generateID(),
				ArchiverImpl.sharedArchiver()));
	}

	/**
	 * Records an event with optional properties.
	 * 
	 * @param name
	 *            Event name
	 * @param properties
	 *            Event properties or null
	 */
	// TODO: We should allow for recording properties as numbers or strings.
	public void record(final String name,
			final HashMap<String, String> properties) {
		dataExecutor.execute(trackingRunnables.record(name, properties,
				ArchiverImpl.sharedArchiver(), this));

		// The main activity's onCreate method will likely not be called
		// frequently enough to re-verify.
		// In most cases this will only be checking the expiration date.
		verifyForTracking();
	}

	/**
	 * Convenience method for recording an event without properties.
	 * 
	 * @param name
	 *            Event name
	 */
	public void record(final String name) {
		record(name, null);
	}

	/**
	 * Records an event only once per identity.
	 * 
	 * @param name
	 *            Event Name
	 */
	public void recordOnce(final String name) {
		dataExecutor.execute(trackingRunnables.recordOnce(name,
				ArchiverImpl.sharedArchiver(), this));
	}

	/**
	 * Sets one or more properties.
	 * 
	 * @param properties
	 *            User properties
	 */
	// TODO: We should allow for recording properties as numbers or strings.
	public void set(final HashMap<String, String> properties) {
		dataExecutor.execute(trackingRunnables.set(properties,
				ArchiverImpl.sharedArchiver(), this));
	}

	/**
	 * Sets a single property if the value is different from the last set value.
	 * 
	 * @param propertyName
	 * @param value
	 */
	// TODO: We should allow for recording properties as numbers or strings.
	public void setDistinct(final String propertyName, final String value) {
		dataExecutor.execute(trackingRunnables.setDistinct(propertyName,
				value, ArchiverImpl.sharedArchiver(), this));
	}

	/**
	 * Automatically records the following events "Installed App" "Updated App"
	 */
	public void autoRecordInstalls() {

		PackageManager pkgManager = context.getPackageManager();
		String versionName = "";

		try {
			String pkg = context.getPackageName();
			versionName = pkgManager.getPackageInfo(pkg, 0).versionName;
			setDistinct("App Version", versionName);
		} catch (Exception e) {
			// Catch intentionally blank
		}

		// There is no reliable place to store data that will persist between
		// app install and uninstall. We use Archiver's settings store.
		String lastAppVersion = ArchiverImpl.sharedArchiver().getAppVersion();

		if (versionName.equals(lastAppVersion)) {
			// Most common case. No action required.
			return;
		}

		if (lastAppVersion == null) {
			// This is a fresh install
			record("Installed App");
		} else if (!lastAppVersion.equals(versionName)) {
			// This is an update
			record("Updated App");
		}

		ArchiverImpl.sharedArchiver().archiveAppVersion(versionName);
	}

	/**
	 * Automatically collects and sets the following hardware properties as
	 * distinct properties: "Device Manufacturer" : (Asus) "Device Model" :
	 * (Nexus 7) "System Name" : (Android) "System Version" : (4.4)
	 */
	public void autoSetHardwareProperties() {
		setDistinct("Device Manufacturer", android.os.Build.MANUFACTURER);
		setDistinct("Device Model", android.os.Build.MODEL);
		setDistinct("System Name", "Android");
		setDistinct("System Version", android.os.Build.VERSION.RELEASE);
	}

	/**
	 * Automatically collects and sets the following applcation properties as
	 * distinct properties: "App Version" : (1.0) aka versionName "App Build" :
	 * (10) aka versionCode
	 */
	public void autoSetAppProperties() {

		PackageManager pkgManager = context.getPackageManager();

		try {
			String pkg = context.getPackageName();
			String versionName = pkgManager.getPackageInfo(pkg, 0).versionName;
			setDistinct("App Version", versionName);
		} catch (Exception e) {
			// Catch intentionally blank
		}

		try {
			String pkg = context.getPackageName();
			int versionCode = pkgManager.getPackageInfo(pkg, 0).versionCode;
			setDistinct("App Build", String.valueOf(versionCode));
		} catch (Exception e) {
			// Catch intentionally blank
		}
	}

	/************************************************
	 * VerificationDelegateInterface methods
	 ************************************************/
	@Override
	public void verificationComplete(final boolean success,
			final boolean doTrack, final String baseUrl,
			final long expirationDate) {

		// We have 3 cases here.
		// 1. verification URL request was unsuccessful.
		// - We will continue to track but not send any data to KM trk
		// 2. verification URL request was successful. !doTrack
		// 3. verification URL request was successful. doTrack

		if (!success) {
			// Do Track by default.
			trackingRunnables = new TrackingRunnablesTrackingState();
			ArchiverImpl.sharedArchiver().archiveDoTrack(true);

			// Do not send by default.
			sender.disableSending();
			ArchiverImpl.sharedArchiver().archiveDoSend(false);

			// Do not modify baseUrl.
			return;
		}

		long maxExpDate = (System.currentTimeMillis() + FAILSAFE_MAX_VERIFICATION_DUR);

		ArchiverImpl.sharedArchiver().archiveVerificationExpDate(
				Math.min(expirationDate, maxExpDate));
		if (!doTrack) {
			trackingRunnables = new TrackingRunnablesNonTrackingState();
			sender.disableSending();
		} else {
			trackingRunnables = new TrackingRunnablesTrackingState();
			// If we should be tracking, then we should be sending
			sender.enableSending();
			ArchiverImpl.sharedArchiver().archiveDoSend(true);
		}

		ArchiverImpl.sharedArchiver().archiveDoTrack(doTrack);

		ArchiverImpl.sharedArchiver().archiveBaseUrl(baseUrl);
	}

	
	/************************************************
	 * Deprecated Public Methods
	 ************************************************/

	/**
	 * @deprecated use {@link sharedAPI(String apiKey)} instead. All requests
	 *             are now made over https. secure(boolean) is ignored.
	 * 
	 *             Initializes and/or returns the KISSmetricsAPI singleton
	 *             instance.
	 * 
	 * @param apiKey
	 *            KISSmetrics product key
	 * @param context
	 *            Android application context
	 * @param secure
	 *            !Ignored!
	 * @return singleton instance of the KISSmeticsAPI
	 */
	@Deprecated
	public static synchronized KISSmetricsAPI sharedAPI(String apiKey,
			Context context, boolean secure) {
		if (sharedAPI == null) {
			sharedAPI = new KISSmetricsAPI(apiKey, context);
		}
		return sharedAPI;
	}

	/**
	 * @deprecated use {@link record(String name, HashMap<String, String>
	 *             properties)} instead. 'recordEvent' method name has been
	 *             changed to 'record' for consistency across our various APIs.
	 * 
	 *             Records an event with optional properties.
	 * 
	 * @param name
	 *            Event name
	 * @param properties
	 *            Event properties or null
	 */
	@Deprecated
	public void recordEvent(String name, HashMap<String, String> properties) {
		record(name, properties);
	}

	/**
	 * @deprecated use {@link set(HashMap<String, String> properties)} instead.
	 *             'setProperties' method name has been changed to 'set' for
	 *             consistency across our various APIs.
	 * 
	 * @param properties
	 *            User properties
	 */
	@Deprecated
	public void setProperties(HashMap<String, String> properties) {
		set(properties);
	}

}
