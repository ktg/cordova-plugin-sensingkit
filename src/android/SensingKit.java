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

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONException;
import org.sensingkit.sensingkitlib.SKException;
import org.sensingkit.sensingkitlib.SKSensorDataListener;
import org.sensingkit.sensingkitlib.SKSensorModuleType;
import org.sensingkit.sensingkitlib.SensingKitLib;
import org.sensingkit.sensingkitlib.SensingKitLibInterface;
import org.sensingkit.sensingkitlib.data.SKSensorData;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

public class SensingKit extends CordovaPlugin
{
	private static final OkHttpClient client = new OkHttpClient();
	private static final Logger logger = Logger.getLogger(SensingKit.class.getSimpleName());
	private SensingKitLibInterface sensingKit;
	private NanoHTTPD webServer = null;
	private final Map<String, SKSensorModuleType> sensors = new HashMap<String, SKSensorModuleType>();

	@Override
	public void initialize(final CordovaInterface cordova, final CordovaWebView webView)
	{
		super.initialize(cordova, webView);
		try
		{
			sensingKit = SensingKitLib.getSensingKitLib(cordova.getActivity());
			for (SKSensorModuleType sensorType : SKSensorModuleType.values())
			{
				try
				{
					sensingKit.registerSensorModule(sensorType);
				}
				catch (Exception e)
				{
					logger.log(Level.WARNING, e.getMessage(), e);
				}
				if (sensingKit.isSensorModuleRegistered(sensorType))
				{
					sensors.put(sensorType.toString().toLowerCase().replace('_', '-'), sensorType);
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
		if (action.equals("start") && args.length() == 1)
		{
			startSensing(args.getString(0));
			return true;
		}
		else if(action.equals("isRunning"))
		{
			callbackContext.success(Boolean.toString(webServer != null));
			return true;
		}
		else if(action.equals("stop"))
		{
			stopSensing();
			return true;
		}
		return false;
	}

	private String getIPAddress()
	{
		try
		{
			final List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
			for (final NetworkInterface intf : interfaces)
			{
				final List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
				for (final InetAddress addr : addrs)
				{
					if (!addr.isLoopbackAddress())
					{
						String sAddr = addr.getHostAddress();
						//boolean isIPv4 = InetAddressUtils.isIPv4Address(sAddr);
						boolean isIPv4 = sAddr.indexOf(':') < 0;
						if (isIPv4)
							return sAddr;
					}
				}
			}
		}
		catch (Exception ex)
		{
			logger.log(Level.INFO, ex.getMessage(), ex);
		} // for now eat exceptions
		return "";
	}

	private void stopSensing()
	{
		if(webServer != null)
		{
			webServer.stop();
			webServer = null;
		}
	}

	private void startSensing(final String url)
	{
		if(webServer == null)
		{
			webServer = new NanoHTTPD(8080)
			{
				private String[] parseURI(String uri)
				{
					if (uri == null)
					{
						return new String[]{};
					}
					if (uri.startsWith("/"))
					{
						uri = uri.substring(1);
					}
					if (uri.endsWith("/"))
					{
						uri = uri.substring(0, uri.length() - 1);
					}

					uri = uri.toLowerCase();
					return uri.split("/");
				}

				@Override
				public Response serve(IHTTPSession session)
				{
					String[] path = parseURI(session.getUri());
					if (path.length < 1 || path[0].length() < 1)
					{
						StringBuilder res = new StringBuilder("<html><head><title>Databox Mobile Source</title></head><body>");
						res.append("<h1>Databox Mobile Source</h1>");
						res.append("<h2>Available Sensors</h2>");
						res.append("<ul>");
						for (String sensor : sensors.keySet())
						{
							res.append("<li>").append(sensor).append("</li>");
						}
						res.append("</ul></body></html>\n");
						return newFixedLengthResponse(res.toString());
					}
					final SKSensorModuleType sensor = sensors.get(path[0]);
					if (sensor == null)
					{
						return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Sensor \"" + path[0] + "\" Not Found"); // TODO: Take name out
					}

					final PipedInputStream in = new PipedInputStream();
					try
					{
						final PipedOutputStream out = new PipedOutputStream(in);
						sensingKit.subscribeSensorDataListener(sensor, new SKSensorDataListener()
						{
							@Override
							public void onDataReceived(final SKSensorModuleType sensorType, final SKSensorData sensorData)
							{
								try
								{
									logger.info(sensorData.getDataInCSV());
									out.write((sensorData.getDataInCSV() + "\n").getBytes(Charset.forName("UTF-8")));
								}
								catch (Exception e)
								{
									try
									{
										sensingKit.unsubscribeSensorDataListener(sensorType, this);
									}
									catch (SKException e1)
									{
										e1.printStackTrace();
									}
									try
									{
										sensingKit.stopContinuousSensingWithSensor(sensorType);
									}
									catch (SKException e1)
									{
										e1.printStackTrace();
									}
									try
									{
										out.close();
									}
									catch (IOException e1)
									{
										e1.printStackTrace();
									}
									//log("No longer streaming " + sensorName + " data to " + ip + ".");
								}
							}
						});
						if (!sensingKit.isSensorModuleRegistered(sensor))
						{
							sensingKit.registerSensorModule(sensor);
						}
						if (!sensingKit.isSensorModuleSensing(sensor))
						{
							sensingKit.startContinuousSensingWithSensor(sensor);
						}

						//log("Now streaming " + sensorName + " data to " + ip + ".");
					}
					catch (SKException e)
					{
						logger.log(Level.WARNING, e.getMessage(), e);
					}
					catch (IOException e)
					{
						logger.log(Level.WARNING, e.getMessage(), e);
					}

					return newChunkedResponse(Response.Status.OK, MIME_PLAINTEXT, in);
				}
			};
			try
			{
				webServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

				HttpUrl query = HttpUrl.parse(url).newBuilder()
						.addPathSegments("ui/set-mobile-ip")
						.addQueryParameter("ip", getIPAddress()).build();

				logger.info(query.toString());
				okhttp3.Response response = client.newCall(new Request.Builder()
						.url(query)
						.build()).execute();
				logger.info(response.code() + ": " + response.body().toString());
			}
			catch (IOException e)
			{
				logger.log(Level.WARNING, e.getMessage(), e);
			}
		}
	}
}