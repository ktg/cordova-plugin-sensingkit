/*
 * Artcodes recognises a different marker scheme that allows the
 * creation of aesthetically pleasing, even beautiful, codes.
 * Copyright (C) 2013-2016  The University of Nottingham
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as published
 *     by the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package uk.ac.nott.mrl.sensingKit;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.security.KeyChain;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;
import okhttp3.*;
import okio.BufferedSink;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.engine.SystemWebView;
import org.apache.cordova.engine.SystemWebViewClient;
import org.apache.cordova.engine.SystemWebViewEngine;
import org.json.JSONArray;
import org.json.JSONException;
import org.sensingkit.sensingkitlib.*;
import org.sensingkit.sensingkitlib.data.SKSensorData;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SensingKit extends CordovaPlugin
{
	private static String getSensorName(SKSensorModuleType type)
	{
		return toTitleCase(type.name().toLowerCase().replace("_", " "));
	}

	private static String toTitleCase(String input)
	{
		StringBuilder titleCase = new StringBuilder();
		boolean nextTitleCase = true;

		for (char c : input.toCharArray())
		{
			if (Character.isSpaceChar(c))
			{
				nextTitleCase = true;
			}
			else if (nextTitleCase)
			{
				c = Character.toTitleCase(c);
				nextTitleCase = false;
			}

			titleCase.append(c);
		}

		return titleCase.toString();
	}

	private class SensorListener implements SKSensorDataListener
	{
		private BlockingQueue<String> queue = new ArrayBlockingQueue<>(100);
		private Charset charset = Charset.forName("UTF-8");
		private boolean connection = false;

		@Override
		public void onDataReceived(final SKSensorModuleType sensorType, final SKSensorData sensorData)
		{
			try
			{
				if (!connection)
				{
					final HttpUrl url = dataURL.newBuilder()
							//.addPathSegment("ui")
							.addPathSegment(getSensorName(sensorType))
							.addPathSegment("data").build();

					final RequestBody requestBody = new RequestBody()
					{
						@Override
						public MediaType contentType()
						{
							return MediaType.parse("text/csv");
						}

						@Override
						public void writeTo(BufferedSink sink)
						{
							logger.info("Started writing " + getSensorName(sensorType));
							while (connection)
							{
								try
								{
									String data = queue.take();
									if (!data.isEmpty())
									{
										sink.writeString(data, charset);
										sink.flush();
									}
								}
								catch (Exception e)
								{
									connection = false;
									logger.warning(e.getMessage());
								}
							}
							logger.info("Finished writing " + getSensorName(sensorType));
						}
					};

					connection = true;
					final Request request = new Request.Builder()
							.url(url)
							.post(requestBody)
							.build();

					client.newCall(request).enqueue(new Callback()
					{
						@Override
						public void onFailure(final Call call, final IOException e)
						{
							logger.warning(e.getMessage());
							connection = false;
						}

						@Override
						public void onResponse(final Call call, final Response response)
						{
							logger.warning(response.toString());
							connection = false;
						}
					});
				}
				queue.put(sensorData.getDataInCSV() + "\n");
			}
			catch (Exception e)
			{
				e.printStackTrace();
				try
				{
					sensingKit.unsubscribeSensorDataListener(sensorType, this);
				}
				catch (Exception e1)
				{
					e1.printStackTrace();
				}
				try
				{
					sensingKit.stopContinuousSensingWithSensor(sensorType);
				}
				catch (Exception e1)
				{
					e1.printStackTrace();
				}
				connection = false;
				//log("No longer streaming " + sensorName + " data to " + ip + ".");
			}
		}
	}

	private class KeyChainBroadcastReceiver extends BroadcastReceiver
	{

		@Override
		public void onReceive(final Context context, final Intent intent)
		{
			trustManager = null;
		}
	}

	private static final String TAG = SensingKit.class.getSimpleName();
	private static final int LOCATION_PERMISSION = 387;
	private static final int INSTALL_KEYCHAIN_CODE = 692;
	private static final Logger logger = Logger.getLogger(uk.ac.nott.mrl.sensingKit.SensingKit.class.getSimpleName());
	private SensingKitLibInterface sensingKit;
	private Certificate cert;
	private final Collection<SKSensorModuleType> sensors = new HashSet<>();
	private final Map<SKSensorModuleType, SensorListener> listeners = new HashMap<>();
	private OkHttpClient client = new OkHttpClient.Builder()
			.readTimeout(0, TimeUnit.MINUTES)
			.writeTimeout(0, TimeUnit.MINUTES)
			.build();
	private HttpUrl dataURL;
	private SslErrorHandler sslHandler = null;
	private X509TrustManager trustManager;

	private X509TrustManager getTrustManager()
	{
		if (trustManager == null)
		{
			createTrustManager();
		}
		return trustManager;
	}

	private void createTrustManager()
	{
		KeyStore keyStore = null;
		try
		{
			keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
			keyStore.load(null, null);
			KeyStore systemKeyStore = KeyStore.getInstance("AndroidCAStore");
			systemKeyStore.load(null, null);
			final Enumeration<String> aliases = systemKeyStore.aliases();
			while (aliases.hasMoreElements())
			{
				String alias = aliases.nextElement();
				Certificate certificate = systemKeyStore.getCertificate(alias);
				keyStore.setCertificateEntry(alias, certificate);

			}
			if (cert != null)
			{
				keyStore.setCertificateEntry("Databox", cert);
			}
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}

		try
		{
			final TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			trustManagerFactory.init(keyStore);

			for (TrustManager manager : trustManagerFactory.getTrustManagers())
			{
				if (manager instanceof X509TrustManager)
				{
					trustManager = (X509TrustManager) manager;
					break;
				}
			}

			createHttpClient();
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
	}

	private void createHttpClient() throws GeneralSecurityException
	{
		final OkHttpClient.Builder builder = new OkHttpClient.Builder()
				.readTimeout(0, TimeUnit.MINUTES)
				.writeTimeout(0, TimeUnit.MINUTES);

		if (trustManager != null)
		{
			final SSLContext sslContext = SSLContext.getInstance("TLS");
			sslContext.init(null, new TrustManager[]{trustManager}, null);

			builder.sslSocketFactory(sslContext.getSocketFactory(), trustManager);
		}
//		if (dataURL != null)
//		{
//			try
//			{
//				KeyStore keyStore = KeyStore.getInstance("AndroidCAStore");
//				keyStore.load(null, null);
//				final String key = keyStore.getCertificate("Databox").getPublicKey().toString();
//				builder.certificatePinner(new CertificatePinner.Builder()
//						.add(dataURL.host(), key)
//						.build());
//			}
//			catch (Exception e)
//			{
//				e.printStackTrace();
//			}
//		}
		// TODO !!!
		builder.hostnameVerifier((hostname, session) -> true);
		builder.cookieJar(new CookieJar()
		{
			private final CookieManager cookieManager = CookieManager.getInstance();

			@Override
			public void saveFromResponse(HttpUrl url, List<Cookie> cookies)
			{
				String urlString = url.toString();

				for (Cookie cookie : cookies)
				{
					cookieManager.setCookie(urlString, cookie.toString());
				}
			}

			@Override
			public List<Cookie> loadForRequest(HttpUrl url)
			{
				String urlString = url.toString();
				String cookiesString = cookieManager.getCookie(urlString);

				if (cookiesString != null && !cookiesString.isEmpty())
				{
					//We can split on the ';' char as the cookie manager only returns cookies
					//that match the url and haven't expired, so the cookie attributes aren't included
					String[] cookieHeaders = cookiesString.split(";");
					List<Cookie> cookies = new ArrayList<>(cookieHeaders.length);

					for (String header : cookieHeaders)
					{
						cookies.add(Cookie.parse(url, header));
					}

					return cookies;
				}

				return Collections.emptyList();
			}
		});
		client = builder.build();
	}

	@Override
	public void initialize(final CordovaInterface cordova, final CordovaWebView webView)
	{
		super.initialize(cordova, webView);

		new Handler(Looper.getMainLooper()).post(() -> {
			final SystemWebView view = (SystemWebView) webView.getView();
			view.setWebViewClient(new SystemWebViewClient((SystemWebViewEngine) webView.getEngine())
			{
				@Override
				public void onReceivedSslError(final WebView view, final SslErrorHandler handler, final SslError error)
				{
					try
					{
						final SslCertificate cert = error.getCertificate();
						final Field field = cert.getClass().getDeclaredField("mX509Certificate");
						field.setAccessible(true);
						final X509Certificate[] chain = {(X509Certificate) field.get(cert)};
						final X509TrustManager x509TrustManager = getTrustManager();
						x509TrustManager.checkServerTrusted(chain, "generic");
						Log.i(TAG, "Cert OK");
						handler.proceed();
					}
					catch (Exception e)
					{
						if (cert == null && error.getUrl().endsWith("/api/connect"))
						{
							sslHandler = handler;
							final Uri uri = Uri.parse(error.getUrl());
							installCert("http://" + uri.getHost());
						}
						Log.e(TAG, "SSL Error: " + error.getUrl(), e);
						handler.cancel();
					}
				}
			});
			webView.clearCache();
			view.reload();
		});

		try
		{
			sensingKit = SensingKitLib.getSensingKitLib(cordova.getActivity());
			for (final SKSensorModuleType sensorType : SKSensorModuleType.values())
			{
				try
				{
					if (sensorType != SKSensorModuleType.AUDIO_LEVEL)
					{
						sensingKit.registerSensorModule(sensorType);
					}
				}
				catch (Exception e)
				{
					logger.log(Level.WARNING, e.getMessage(), e);
				}
				if (sensingKit.isSensorModuleRegistered(sensorType))
				{
					sensors.add(sensorType);
				}
			}
		}
		catch (SKException e)
		{
			logger.log(Level.SEVERE, e.getMessage(), e);
		}

		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
		{
			IntentFilter intentFilter = new IntentFilter(KeyChain.ACTION_KEYCHAIN_CHANGED);
			IntentFilter intentFilter2 = new IntentFilter(KeyChain.ACTION_TRUST_STORE_CHANGED);
			KeyChainBroadcastReceiver receiver = new KeyChainBroadcastReceiver();
			cordova.getActivity().registerReceiver(receiver, intentFilter);
			cordova.getActivity().registerReceiver(receiver, intentFilter2);
		}
	}

	@Override
	public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException
	{
		switch (action)
		{
			case "stop":
				stopSensing();
				callbackContext.success();
				return true;
			case "listSensors":
			{
				final JSONArray array = new JSONArray();
				for (final SKSensorModuleType sensor : sensors)
				{
					array.put(getSensorName(sensor));
				}
				callbackContext.success(array);
				return true;
			}
			case "startSensors":
			{
				dataURL = HttpUrl.parse(args.getString(1)).newBuilder().addPathSegment("ui").build();
				try
				{
					createHttpClient();
				}
				catch (Exception e)
				{
					e.printStackTrace();
				}

				final JSONArray array = args.getJSONArray(0);
				final Set<String> sensors = new HashSet<>();
				for (int index = 0; index < array.length(); index++)
				{
					sensors.add(array.getString(index));
				}

				startSensors(sensors);
				callbackContext.success();
				return true;
			}
		}
		return false;
	}

	private void installCert(final String urlBase)
	{
		Log.i(TAG, urlBase);
		final HttpUrl certUrl = HttpUrl.parse(urlBase)
				.newBuilder()
				.addPathSegment("cert.pem")
				.build();

		final Request certRequest = new Request.Builder()
				.url(certUrl)
				.build();

		client.newCall(certRequest).enqueue(new Callback()
		{
			@Override
			public void onFailure(final Call call, final IOException e)
			{
				e.printStackTrace();
				sslHandler.cancel();
			}

			@Override
			public void onResponse(final Call call, final Response response)
			{
				try
				{
					final byte[] certBytes = response.body().bytes();
					final ContextThemeWrapper themedContext = new ContextThemeWrapper(cordova.getActivity(), android.R.style.Theme_DeviceDefault_Light_Dialog);
					final AlertDialog.Builder builder = new AlertDialog.Builder(themedContext);
					builder.setTitle("Install Certificate")
							.setMessage("Databox requires you to install a certificate to be able to use it securely.")
							.setPositiveButton("Install", (dialog, id) -> {
								try
								{
									CertificateFactory fact = CertificateFactory.getInstance("X.509");
									cert = fact.generateCertificate(new ByteArrayInputStream(certBytes));

									final Intent installIntent = KeyChain.createInstallIntent();
									installIntent.putExtra(KeyChain.EXTRA_CERTIFICATE, certBytes);
									installIntent.putExtra(KeyChain.EXTRA_NAME, "Databox");

									cordova.startActivityForResult(SensingKit.this, installIntent, INSTALL_KEYCHAIN_CODE);
								}
								catch (Exception e)
								{
									Log.i(TAG, e.getMessage(), e);
								}
							})
							.setNegativeButton("Cancel", (dialog, id) -> sslHandler.cancel());
					// Create the AlertDialog object and return it#
					cordova.getActivity().runOnUiThread(() -> {
						AlertDialog dialog = builder.create();
						dialog.show();
					});
				}
				catch (Exception e)
				{
					Log.i(TAG, e.getMessage(), e);
				}
			}
		});
	}

	private void startSensor(final SKSensorModuleType sensor) throws SKException
	{
		//logger.info("Starting " + getSensorName(sensor));
		if (sensor == SKSensorModuleType.LOCATION && ContextCompat.checkSelfPermission(cordova.getActivity(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
		{
			ActivityCompat.requestPermissions(cordova.getActivity(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION);
		}
		else
		{
			final SensorListener listener = new SensorListener();
			sensingKit.subscribeSensorDataListener(sensor, listener);
			listeners.put(sensor, listener);
			if (!sensingKit.isSensorModuleRegistered(sensor))
			{
				sensingKit.registerSensorModule(sensor);
			}
			if (!sensingKit.isSensorModuleSensing(sensor))
			{
				sensingKit.startContinuousSensingWithSensor(sensor);
			}
		}
	}

	private void stopSensing()
	{
		startSensors(Collections.emptySet());
	}

	private void startSensors(final Collection<String> sensors)
	{
		for (final SKSensorModuleType type : this.sensors)
		{
			try
			{
				if (sensingKit.isSensorModuleSensing(type))
				{
					if (!sensors.contains(getSensorName(type)))
					{
						//logger.info("Stopping " + getSensorName(type));
						sensingKit.stopContinuousSensingWithSensor(type);
						final SensorListener listener = listeners.get(type);
						if (listener != null)
						{
							sensingKit.unsubscribeSensorDataListener(type, listener);
							try
							{
								listener.connection = false;
								listener.queue.put("");
							}
							catch (InterruptedException e)
							{
								e.printStackTrace();
							}
							listeners.remove(type);
						}
					}
				}
				else if (sensors.contains(getSensorName(type)))
				{
					startSensor(type);
				}
			}
			catch (SKException e)
			{
				logger.log(Level.WARNING, e.getMessage(), e);
			}
		}
	}

	@Override
	public void onActivityResult(final int requestCode, final int resultCode, final Intent intent)
	{
		if (requestCode == INSTALL_KEYCHAIN_CODE)
		{
			if (resultCode == Activity.RESULT_OK)
			{
				createTrustManager();
				sslHandler.proceed();
			}
			else
			{
				sslHandler.cancel();
			}
		}
		else
		{
			super.onActivityResult(requestCode, resultCode, intent);
		}
	}

	public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults)
	{
		if (requestCode == LOCATION_PERMISSION)
		{
			for (int r : grantResults)
			{
				if (r == PackageManager.PERMISSION_GRANTED)
				{
					try
					{
						startSensor(SKSensorModuleType.LOCATION);
					}
					catch (Throwable e)
					{
						logger.log(Level.WARNING, e.getMessage(), e);
					}
					return;
				}
			}
		}
	}
}
