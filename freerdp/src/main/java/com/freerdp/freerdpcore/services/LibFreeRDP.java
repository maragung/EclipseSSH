/*
   Android FreeRDP JNI Wrapper

   Copyright 2013 Thincast Technologies GmbH, Author: Martin Fleisz

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.

   Adapted for EclipseSSH from FreeRDP 3.31.1
   (client/Android/Studio/freeRDPCore/src/main/java/com/freerdp/freerdpcore/services/LibFreeRDP.java):

   - The upstream file dispatched events through the aFreeRDP application classes
     (GlobalApp/SessionState/BookmarkBase/ApplicationSettingsActivity). This copy
     is dependency-free: newInstance registers the listeners per native instance
     and the static callbacks below dispatch through that registry. The registry
     replaces upstream's single static listener - the JNI bridge instantiates
     this class from JNI_OnLoad and calls its static methods with the instance
     handle as the first argument, so per-instance state must live in a map
     keyed by that handle.
   - setConnectionInfo(Context, long, BookmarkBase) became the lean argument
     builder for a tunneled session; setArguments exposes freerdp_parse_arguments
     directly for anything the builder does not cover.
   - The class and package name are load-bearing: the native bridge's JNI name
     mangling (Java_com_freerdp_freerdpcore_services_LibFreeRDP_*) and its
     FindClass("com/freerdp/freerdpcore/services/LibFreeRDP") lookups are compiled
     into libfreerdp-android.so. Do not rename or move this class.

   The C->Java callback methods (OnPreConnect, OnGraphicsUpdate, ...) must keep
   their exact names and signatures: the native side resolves them with
   GetStaticMethodID at JNI_OnLoad / on each event. Their non-idiomatic naming is
   upstream's contract, not a style choice.
*/

package com.freerdp.freerdpcore.services;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LibFreeRDP
{
	private static final String TAG = "LibFreeRDP";
	private static boolean mHasH264 = false;
	private static boolean mHasCameraRedirection = false;

	// Per-instance listeners, keyed by the native instance handle the bridge
	// passes as the first argument of every callback. See the class comment.
	private static final Map<Long, Listeners> mListeners = new ConcurrentHashMap<>();

	// Connected-instance bookkeeping. freeInstance waits on this so a freed
	// instance cannot outlive its disconnect callback - the same lifecycle
	// contract upstream ships.
	private static final Object mInstanceStateLock = new Object();
	private static final Map<Long, Boolean> mInstanceState = new ConcurrentHashMap<>();

	public static final long VERIFY_CERT_FLAG_NONE = 0x00;
	public static final long VERIFY_CERT_FLAG_LEGACY = 0x02;
	public static final long VERIFY_CERT_FLAG_REDIRECT = 0x10;
	public static final long VERIFY_CERT_FLAG_GATEWAY = 0x20;
	public static final long VERIFY_CERT_FLAG_CHANGED = 0x40;
	public static final long VERIFY_CERT_FLAG_MISMATCH = 0x80;
	public static final long VERIFY_CERT_FLAG_MATCH_LEGACY_SHA1 = 0x100;
	public static final long VERIFY_CERT_FLAG_FP_IS_PEM = 0x200;

	// Keep in sync with android_freerdp.c.
	public static final int EXPERIMENTAL_REMOTEAPP = 0;
	public static final int EXPERIMENTAL_CAMERA = 1;

	static
	{
		try
		{
			System.loadLibrary("freerdp-android");

			/* Load dependent libraries too to trigger JNI_OnLoad calls */
			String version = freerdp_get_jni_version();
			String[] versions = version.split("[\\.-]");
			if (versions.length > 0)
			{
				System.loadLibrary("freerdp-client" + versions[0]);
				System.loadLibrary("freerdp" + versions[0]);
				System.loadLibrary("winpr" + versions[0]);
			}
			Pattern pattern = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+).*");
			Matcher matcher = pattern.matcher(version);
			if (!matcher.matches() || (matcher.groupCount() < 3))
				throw new RuntimeException("APK broken: native library version " + version +
				                           " does not meet requirements!");
			int major = Integer.parseInt(Objects.requireNonNull(matcher.group(1)));
			int minor = Integer.parseInt(Objects.requireNonNull(matcher.group(2)));
			int patch = Integer.parseInt(Objects.requireNonNull(matcher.group(3)));

			if (major > 2)
				mHasH264 = freerdp_has_h264();
			else if (minor > 5)
				mHasH264 = freerdp_has_h264();
			else if ((minor == 5) && (patch >= 1))
				mHasH264 = freerdp_has_h264();
			else
				throw new RuntimeException("APK broken: native library version " + version +
				                           " does not meet requirements!");
			mHasCameraRedirection = freerdp_has_camera_redirection();
			Log.i(TAG, "Successfully loaded native library. H264 is " +
			               (mHasH264 ? "supported" : "not available") + ", camera redirection is " +
			               (mHasCameraRedirection ? "supported" : "not available"));
		}
		catch (UnsatisfiedLinkError e)
		{
			Log.e(TAG, "Failed to load library: " + e);
			throw e;
		}
	}

	public static boolean hasH264Support()
	{
		return mHasH264;
	}

	public static boolean hasCameraRedirectionSupport()
	{
		return mHasCameraRedirection;
	}

	private static native boolean freerdp_has_h264();

	private static native boolean freerdp_has_camera_redirection();

	private static native String freerdp_get_jni_version();

	private static native String freerdp_get_version();

	private static native String freerdp_get_build_revision();

	private static native String freerdp_get_build_config();

	private static native long freerdp_new(Context context);

	private static native void freerdp_free(long inst);

	private static native boolean freerdp_parse_arguments(long inst, String[] args);

	private static native boolean freerdp_connect(long inst);

	private static native boolean freerdp_disconnect(long inst);

	private static native boolean freerdp_update_graphics(long inst, Bitmap bitmap, int x, int y,
	                                                      int width, int height);

	private static native boolean freerdp_send_cursor_event(long inst, int x, int y, int flags);

	private static native boolean freerdp_send_key_event(long inst, int keycode, boolean down);

	private static native boolean freerdp_send_unicodekey_event(long inst, int keycode,
	                                                            boolean down);

	private static native boolean freerdp_is_unicode_input_supported(long inst);

	private static native boolean freerdp_send_clipboard_data(long inst, String data);

	private static native boolean freerdp_send_clipboard_image_data(long inst, byte[] data,
	                                                                String mimeType);

	private static native boolean freerdp_send_monitor_layout(long inst, int width, int height);

	private static native String freerdp_get_last_error_string(long inst);

	private static final class Listeners
	{
		final EventListener eventListener;
		final UIEventListener uiEventListener;

		Listeners(EventListener eventListener, UIEventListener uiEventListener)
		{
			this.eventListener = eventListener;
			this.uiEventListener = uiEventListener;
		}
	}

	/**
	 * Creates a native FreeRDP instance with the given listeners attached to it.
	 * Either listener may be null; a null listener simply means the matching
	 * events are dropped. Returns 0 on failure.
	 */
	public static long newInstance(Context context, EventListener eventListener,
	                               UIEventListener uiEventListener)
	{
		long inst = freerdp_new(context);
		if (inst != 0)
			mListeners.put(inst, new Listeners(eventListener, uiEventListener));
		return inst;
	}

	public static void freeInstance(long inst)
	{
		synchronized (mInstanceStateLock)
		{
			if (Boolean.TRUE.equals(mInstanceState.get(inst)))
			{
				freerdp_disconnect(inst);
			}
			while (Boolean.TRUE.equals(mInstanceState.get(inst)))
			{
				try
				{
					mInstanceStateLock.wait();
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					throw new RuntimeException(e);
				}
			}
		}
		freerdp_free(inst);
		mListeners.remove(inst);
	}

	public static boolean connect(long inst)
	{
		synchronized (mInstanceStateLock)
		{
			if (Boolean.TRUE.equals(mInstanceState.get(inst)))
			{
				throw new RuntimeException("instance already connected");
			}
		}
		return freerdp_connect(inst);
	}

	public static boolean disconnect(long inst)
	{
		synchronized (mInstanceStateLock)
		{
			if (Boolean.TRUE.equals(mInstanceState.get(inst)))
			{
				return freerdp_disconnect(inst);
			}
			return true;
		}
	}

	public static boolean cancelConnection(long inst)
	{
		return freerdp_disconnect(inst);
	}

	/**
	 * Configures an instance for a tunneled RDP session: software GDI (frames are
	 * blitted into a Java-owned Bitmap, not an EGL surface), clipboard both ways,
	 * the display channel so the server can learn the viewer's resolution, and a
	 * fixed 32bpp desktop sized to the viewer. Credentials are optional here - if
	 * they are withheld, NLA challenges arrive through OnAuthenticate, which is
	 * how a viewer that stores no password connects.
	 *
	 * The server certificate is accepted rather than relayed for a decision: the
	 * session runs through the SSH tunnel, whose endpoint identity is already
	 * anchored by the SSH host key this app verifies. OnVerifyCertificateEx is
	 * still delivered if the engine asks.
	 */
	public static boolean setConnectionInfo(long inst, String hostname, int port, String username,
	                                        String domain, String password, int width, int height)
	{
		List<String> args = new ArrayList<>();

		args.add(TAG); // argv[0]; consumed by freerdp_parse_arguments
		args.add("/gdi:sw");
		args.add("/v:" + hostname);
		args.add("/port:" + port);
		if (!username.isEmpty())
			args.add("/u:" + username);
		if (!domain.isEmpty())
			args.add("/d:" + domain);
		if (!password.isEmpty())
			args.add("/p:" + password);
		if ((width > 0) && (height > 0))
			args.add(String.format(Locale.US, "/size:%dx%d", width, height));
		args.add("/bpp:32");
		args.add("/clipboard");
		args.add("/disp");
		args.add("/kbd:unicode:on");
		args.add("/cert:ignore");
		args.add("/log-level:INFO");
		return freerdp_parse_arguments(inst, args.toArray(new String[0]));
	}

	/**
	 * Configures an instance from a raw FreeRDP argument list
	 * (https://github.com/FreeRDP/FreeRDP/wiki/CommandLine-Interface syntax,
	 * without the leading program name). setConnectionInfo covers the standard
	 * tunneled session; this is the escape hatch for anything it does not.
	 */
	public static boolean setArguments(long inst, List<String> args)
	{
		return freerdp_parse_arguments(inst, args.toArray(new String[0]));
	}

	public static boolean updateGraphics(long inst, Bitmap bitmap, int x, int y, int width,
	                                     int height)
	{
		return freerdp_update_graphics(inst, bitmap, x, y, width, height);
	}

	public static boolean sendCursorEvent(long inst, int x, int y, int flags)
	{
		return freerdp_send_cursor_event(inst, x, y, flags);
	}

	public static boolean sendKeyEvent(long inst, int keycode, boolean down)
	{
		return freerdp_send_key_event(inst, keycode, down);
	}

	public static boolean sendUnicodeKeyEvent(long inst, int keycode, boolean down)
	{
		return freerdp_send_unicodekey_event(inst, keycode, down);
	}

	public static boolean isUnicodeInputSupported(long inst)
	{
		return freerdp_is_unicode_input_supported(inst);
	}

	public static boolean sendClipboardData(long inst, String data)
	{
		return freerdp_send_clipboard_data(inst, data);
	}

	public static boolean sendClipboardImageData(long inst, byte[] data, String mimeType)
	{
		return freerdp_send_clipboard_image_data(inst, data, mimeType);
	}

	public static boolean sendMonitorLayout(long inst, int width, int height)
	{
		return freerdp_send_monitor_layout(inst, width, height);
	}

	public static String getVersion()
	{
		return freerdp_get_version();
	}

	/**
	 * The last error the engine recorded for this instance, as a human-readable
	 * string. Meaningful after OnConnectionFailure or OnDisconnected.
	 */
	public static String getLastErrorString(long inst)
	{
		return freerdp_get_last_error_string(inst);
	}

	private static void OnConnectionSuccess(long inst)
	{
		Listeners l = mListeners.get(inst);
		if ((l != null) && (l.eventListener != null))
			l.eventListener.OnConnectionSuccess(inst);
		synchronized (mInstanceStateLock)
		{
			mInstanceState.put(inst, Boolean.TRUE);
			mInstanceStateLock.notifyAll();
		}
	}

	private static void OnConnectionFailure(long inst)
	{
		Listeners l = mListeners.get(inst);
		if ((l != null) && (l.eventListener != null))
			l.eventListener.OnConnectionFailure(inst);
		synchronized (mInstanceStateLock)
		{
			mInstanceState.remove(inst);
			mInstanceStateLock.notifyAll();
		}
	}

	private static void OnPreConnect(long inst)
	{
		Listeners l = mListeners.get(inst);
		if ((l != null) && (l.eventListener != null))
			l.eventListener.OnPreConnect(inst);
	}

	private static void OnDisconnecting(long inst)
	{
		Listeners l = mListeners.get(inst);
		if ((l != null) && (l.eventListener != null))
			l.eventListener.OnDisconnecting(inst);
	}

	private static void OnDisconnected(long inst)
	{
		Listeners l = mListeners.get(inst);
		if ((l != null) && (l.eventListener != null))
			l.eventListener.OnDisconnected(inst);
		synchronized (mInstanceStateLock)
		{
			mInstanceState.remove(inst);
			mInstanceStateLock.notifyAll();
		}
	}

	private static void OnSettingsChanged(long inst, int width, int height, int bpp)
	{
		UIEventListener uiEventListener = uiListener(inst);
		if (uiEventListener != null)
			uiEventListener.OnSettingsChanged(width, height, bpp);
	}

	private static boolean OnAuthenticate(long inst, StringBuilder username, StringBuilder domain,
	                                      StringBuilder password)
	{
		UIEventListener uiEventListener = uiListener(inst);
		if (uiEventListener != null)
			return uiEventListener.OnAuthenticate(username, domain, password);
		return false;
	}

	private static boolean OnGatewayAuthenticate(long inst, StringBuilder username,
	                                             StringBuilder domain, StringBuilder password)
	{
		UIEventListener uiEventListener = uiListener(inst);
		if (uiEventListener != null)
			return uiEventListener.OnGatewayAuthenticate(username, domain, password);
		return false;
	}

	private static int OnVerifyCertificateEx(long inst, String host, long port, String commonName,
	                                       String subject, String issuer, String fingerprint,
	                                       long flags)
	{
		UIEventListener uiEventListener = uiListener(inst);
		if (uiEventListener != null)
			return uiEventListener.OnVerifyCertificateEx(host, port, commonName, subject, issuer,
			                                             fingerprint, flags);
		return 0;
	}

	private static int OnVerifyChangedCertificateEx(long inst, String host, long port,
	                                                String commonName, String subject,
	                                                String issuer, String fingerprint,
	                                                String oldSubject, String oldIssuer,
	                                                String oldFingerprint, long flags)
	{
		UIEventListener uiEventListener = uiListener(inst);
		if (uiEventListener != null)
			return uiEventListener.OnVerifyChangedCertificateEx(host, port, commonName, subject,
			                                                    issuer, fingerprint, oldSubject,
			                                                    oldIssuer, oldFingerprint, flags);
		return 0;
	}

	private static boolean OnExperimentalFeature(long inst, int feature)
	{
		UIEventListener uiEventListener = uiListener(inst);
		if (uiEventListener == null)
			return true;
		return uiEventListener.OnExperimentalFeature(feature);
	}

	private static void OnGraphicsUpdate(long inst, int x, int y, int width, int height)
	{
		UIEventListener uiEventListener = uiListener(inst);
		if (uiEventListener != null)
			uiEventListener.OnGraphicsUpdate(x, y, width, height);
	}

	private static void OnGraphicsResize(long inst, int width, int height, int bpp)
	{
		UIEventListener uiEventListener = uiListener(inst);
		if (uiEventListener != null)
			uiEventListener.OnGraphicsResize(width, height, bpp);
	}

	private static void OnRemoteClipboardChanged(long inst, String data)
	{
		UIEventListener uiEventListener = uiListener(inst);
		if (uiEventListener != null)
			uiEventListener.OnRemoteClipboardChanged(data);
	}

	private static void OnRemoteClipboardImageChanged(long inst, byte[] data)
	{
		UIEventListener uiEventListener = uiListener(inst);
		if (uiEventListener != null)
			uiEventListener.OnRemoteClipboardImageChanged(data);
	}

	private static void OnPointerSet(long inst, int[] pixels, int width, int height, int hotX,
	                                 int hotY)
	{
		UIEventListener uiEventListener = uiListener(inst);
		if (uiEventListener != null)
			uiEventListener.OnPointerSet(pixels, width, height, hotX, hotY);
	}

	private static void OnPointerSetNull(long inst)
	{
		UIEventListener uiEventListener = uiListener(inst);
		if (uiEventListener != null)
			uiEventListener.OnPointerSetNull();
	}

	private static void OnPointerSetDefault(long inst)
	{
		UIEventListener uiEventListener = uiListener(inst);
		if (uiEventListener != null)
			uiEventListener.OnPointerSetDefault();
	}

	private static void OnRailWindowUpdate(long inst, long windowId, int width, int height,
	                                       int[] pixels)
	{
		UIEventListener uiEventListener = uiListener(inst);
		if (uiEventListener != null)
			uiEventListener.OnRailWindowUpdate(windowId, width, height, pixels);
	}

	private static void OnRailWindowMove(long inst, long windowId, int x, int y, int w, int h)
	{
		UIEventListener uiEventListener = uiListener(inst);
		if (uiEventListener != null)
			uiEventListener.OnRailWindowMove(windowId, x, y, w, h);
	}

	private static void OnRailWindowHide(long inst, long windowId)
	{
		UIEventListener uiEventListener = uiListener(inst);
		if (uiEventListener != null)
			uiEventListener.OnRailWindowHide(windowId);
	}

	private static void OnRailWindowDestroy(long inst, long windowId)
	{
		UIEventListener uiEventListener = uiListener(inst);
		if (uiEventListener != null)
			uiEventListener.OnRailWindowDestroy(windowId);
	}

	private static void OnRailSessionEnd(long inst)
	{
		UIEventListener uiEventListener = uiListener(inst);
		if (uiEventListener != null)
			uiEventListener.OnRailSessionEnd();
	}

	private static void OnRailMonitoredDesktop(long inst, long[] windowIds, long activeWindowId)
	{
		UIEventListener uiEventListener = uiListener(inst);
		if (uiEventListener != null)
			uiEventListener.OnRailMonitoredDesktop(windowIds, activeWindowId);
	}

	private static UIEventListener uiListener(long inst)
	{
		Listeners l = mListeners.get(inst);
		return (l != null) ? l.uiEventListener : null;
	}

	public interface EventListener
	{
		void OnPreConnect(long instance);

		void OnConnectionSuccess(long instance);

		void OnConnectionFailure(long instance);

		void OnDisconnecting(long instance);

		void OnDisconnected(long instance);
	}

	public interface UIEventListener
	{
		void OnSettingsChanged(int width, int height, int bpp);

		boolean OnAuthenticate(StringBuilder username, StringBuilder domain,
		                       StringBuilder password);

		boolean OnGatewayAuthenticate(StringBuilder username, StringBuilder domain,
		                              StringBuilder password);

		int OnVerifyCertificateEx(String host, long port, String commonName, String subject,
		                         String issuer, String fingerprint, long flags);

		int OnVerifyChangedCertificateEx(String host, long port, String commonName, String subject,
		                                String issuer, String fingerprint, String oldSubject,
		                                String oldIssuer, String oldFingerprint, long flags);

		boolean OnExperimentalFeature(int feature);

		void OnGraphicsUpdate(int x, int y, int width, int height);

		void OnGraphicsResize(int width, int height, int bpp);

		void OnRemoteClipboardChanged(String data);

		void OnRemoteClipboardImageChanged(byte[] data);

		void OnPointerSet(int[] pixels, int width, int height, int hotX, int hotY);

		void OnPointerSetNull();

		void OnPointerSetDefault();

		void OnRailWindowUpdate(long windowId, int width, int height, int[] pixels);

		void OnRailWindowMove(long windowId, int x, int y, int w, int h);

		void OnRailWindowHide(long windowId);

		void OnRailWindowDestroy(long windowId);

		void OnRailSessionEnd();

		void OnRailMonitoredDesktop(long[] windowIds, long activeWindowId);
	}
}
