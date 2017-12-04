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
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.security.KeyChain;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.engine.SystemWebView;
import org.apache.cordova.engine.SystemWebViewClient;
import org.apache.cordova.engine.SystemWebViewEngine;
import org.json.JSONArray;
import org.json.JSONException;
import org.sensingkit.sensingkitlib.SKException;
import org.sensingkit.sensingkitlib.SKSensorDataListener;
import org.sensingkit.sensingkitlib.SKSensorModuleType;
import org.sensingkit.sensingkitlib.SensingKitLib;
import org.sensingkit.sensingkitlib.SensingKitLibInterface;
import org.sensingkit.sensingkitlib.data.SKSensorData;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

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
		private BlockingQueue<String> queue = new ArrayBlockingQueue<String>(100);
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

					logger.info(url.toString());

					final RequestBody requestBody = new RequestBody()
					{
						@Override
						public MediaType contentType()
						{
							return MediaType.parse("text/csv");
						}

						@Override
						public void writeTo(BufferedSink sink) throws IOException
						{
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
									e.printStackTrace();
								}
							}
							logger.info("Finished " + getSensorName(sensorType));
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
						public void onResponse(final Call call, final Response response) throws IOException
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

	private static final String TAG = SensingKit.class.getSimpleName();
	private static final int LOCATION_PERMISSION = 387;
	private static final int INSTALL_KEYCHAIN_CODE = 692;
	private static final Logger logger = Logger.getLogger(uk.ac.nott.mrl.sensingKit.SensingKit.class.getSimpleName());
	private SensingKitLibInterface sensingKit;
	private Certificate cert;
	private final Collection<SKSensorModuleType> sensors = new HashSet<SKSensorModuleType>();
	private final Map<SKSensorModuleType, SensorListener> listeners = new HashMap<SKSensorModuleType, SensorListener>();
	private OkHttpClient client = new OkHttpClient.Builder()
			.readTimeout(0, TimeUnit.MINUTES)
			.writeTimeout(0, TimeUnit.MINUTES)
			.build();
	private HttpUrl dataURL;
	private CallbackContext callback;
	private TrustManagerFactory trustManagerFactory;

	private TrustManager[] getTrustManagers()
	{
		if (trustManagerFactory == null)
		{
			createTrustManagerFactory();
		}
		return trustManagerFactory.getTrustManagers();
	}

	private void createTrustManagerFactory()
	{
		KeyStore keyStore = null;
		try
		{
			keyStore = KeyStore.getInstance("AndroidCAStore");
			keyStore.load(null, null);
			if(cert != null)
			{
				keyStore.setCertificateEntry("databox", cert);
			}
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}

		try
		{
			trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			trustManagerFactory.init(keyStore);

			X509TrustManager trustManager = null;
			for (TrustManager manager : trustManagerFactory.getTrustManagers())
			{
				if (manager instanceof X509TrustManager)
				{
					trustManager = (X509TrustManager) manager;
					break;
				}
			}

			final OkHttpClient.Builder builder = new OkHttpClient.Builder()
					.readTimeout(0, TimeUnit.MINUTES)
					.writeTimeout(0, TimeUnit.MINUTES);

			if (trustManager != null)
			{
				final SSLContext sslContext = SSLContext.getInstance("TLS");
				sslContext.init(null, new TrustManager[]{trustManager}, null);

				builder.sslSocketFactory(sslContext.getSocketFactory(), trustManager);
				client = builder.build();
			}
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
	}

	@Override
	public void initialize(final CordovaInterface cordova, final CordovaWebView webView)
	{
		super.initialize(cordova, webView);

		Log.i(TAG, "Setting SystemWebViewClient");
		new Handler(Looper.getMainLooper()).post(new Runnable()
		{
			@Override
			public void run()
			{
				final SystemWebView view = (SystemWebView) webView.getView();
				view.setWebViewClient(new SystemWebViewClient((SystemWebViewEngine) webView.getEngine())
				{
					@Override
					public void onReceivedSslError(final WebView view, final SslErrorHandler handler, final SslError error)
					{
						try
						{
							Log.i(TAG, "SSL Error " + error.getUrl());
							final SslCertificate cert = error.getCertificate();
							final Field field = cert.getClass().getDeclaredField("mX509Certificate");
							field.setAccessible(true);
							final X509Certificate[] chain = {(X509Certificate) field.get(cert)};
							for (TrustManager trustManager : getTrustManagers())
							{
								if (trustManager instanceof X509TrustManager)
								{
									final X509TrustManager x509TrustManager = (X509TrustManager) trustManager;
									try
									{
										x509TrustManager.checkServerTrusted(chain, "generic");
										handler.proceed();
										return;
									}
									catch (Exception e)
									{
										Log.e(TAG, "verify trustManager failed", e);
									}
								}
							}
						}
						catch (Exception e)
						{
							Log.e(TAG, "verify trustManager failed", e);
						}
						handler.cancel();
						//super.onReceivedSslError(view, handler, error);
					}
				});
				webView.clearCache();
				view.reload();
				Log.i(TAG, "Setting SystemWebViewClient2");
			}
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
	}

	@Override
	public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException
	{
		if (action.equals("installCert"))
		{
			installCert(args.getString(0), callbackContext);
			return true;
		}
		else if (action.equals("stop"))
		{
			stopSensing();
			callbackContext.success();
			return true;
		}
		else if (action.equals("listSensors"))
		{
			final JSONArray array = new JSONArray();
			for (final SKSensorModuleType sensor : sensors)
			{
				array.put(getSensorName(sensor));
			}
			callbackContext.success(array);
			return true;
		}
		else if (action.equals("startSensors"))
		{
			dataURL = HttpUrl.parse(args.getString(1)).newBuilder().addPathSegment("ui").build();

			final JSONArray array = args.getJSONArray(0);
			final Set<String> sensors = new HashSet<String>();
			for (int index = 0; index < array.length(); index++)
			{
				sensors.add(array.getString(index));
			}

			startSensors(sensors);
			callbackContext.success();
			return true;
		}
		return false;
	}

	private void installCert(final String urlBase, final CallbackContext callbackContext)
	{
		final HttpUrl certUrl = HttpUrl.parse(urlBase)
				.newBuilder()
				.scheme("http")
				.port(8448)
				.addPathSegment("cert.pem")
				.build();

		Log.i("SensingKit", certUrl.toString());

		final Request certRequest = new Request.Builder()
				.url(certUrl)
				.build();

		callback = callbackContext;
		client.newCall(certRequest).enqueue(new Callback()
		{
			@Override
			public void onFailure(final Call call, final IOException e)
			{
				callbackContext.error(e.getMessage());
			}

			@Override
			public void onResponse(final Call call, final Response response) throws IOException
			{
				ContextThemeWrapper themedContext = new ContextThemeWrapper(cordova.getActivity(), android.R.style.Theme_DeviceDefault_Light_Dialog);
				final AlertDialog.Builder builder = new AlertDialog.Builder(themedContext);
				builder.setTitle("Install Certificate")
						.setMessage("Databox requires you to install a certificate to be able to use it securely.")
						.setPositiveButton("Install", new DialogInterface.OnClickListener()
						{
							public void onClick(DialogInterface dialog, int id)
							{
								try
								{
									final byte[] certBytes = response.body().bytes();

									CertificateFactory fact = CertificateFactory.getInstance("X.509");
									cert = fact.generateCertificate(new ByteArrayInputStream(certBytes));

									final Intent installIntent = KeyChain.createInstallIntent();
									installIntent.putExtra(KeyChain.EXTRA_CERTIFICATE, certBytes);
									installIntent.putExtra(KeyChain.EXTRA_NAME, "Databox");

									// TODO Rel

									cordova.startActivityForResult(SensingKit.this, installIntent, INSTALL_KEYCHAIN_CODE);
								}
								catch (Exception e)
								{
									Log.i(TAG, e.getMessage(), e);
								}
							}
						})
						.setNegativeButton("Cancel", new DialogInterface.OnClickListener()
						{
							public void onClick(DialogInterface dialog, int id)
							{
								// User cancelled the dialog
							}
						});
				// Create the AlertDialog object and return it#
				cordova.getActivity().runOnUiThread(new Runnable()
				{
					@Override
					public void run()
					{
						AlertDialog dialog = builder.create();
						dialog.show();
					}
				});
			}
		});
	}

	private void startSensor(final SKSensorModuleType sensor) throws SKException
	{
		logger.info("Starting " + getSensorName(sensor));
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
		startSensors(Collections.<String>emptySet());
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
						logger.info("Stopping " + getSensorName(type));
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
				else
				{
					if (sensors.contains(getSensorName(type)))
					{
						startSensor(type);
					}
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
			switch (resultCode)
			{
				case Activity.RESULT_OK:
					createTrustManagerFactory();

					callback.success();
					break;
				default:
					super.onActivityResult(requestCode, resultCode, intent);
			}
		}
		else
		{
			super.onActivityResult(requestCode, resultCode, intent);
		}
	}

	public void onRequestPermissionResult(int requestCode, String[] permissions,
	                                      int[] grantResults) throws JSONException
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