class Sensor {
	let type: SKSensorType
	let name: String
	var message: String = ""

	init(_ name: String, _ type: SKSensorType) {
		self.type = type
		self.name = name
	}
}

@objc(SensingKit) class SensingKit : CDVPlugin {
	var sensingKit: SensingKitLib?
	var sensors: [SKSensorType: Sensor] = [:]

	override func pluginInitialize() {
		sensingKit = SensingKitLib.shared()
		sensors = [:]
		let sensorList = [Sensor("Accelerometer", .Accelerometer),
		               Sensor("Altimeter", .Altimeter),
		               Sensor("Battery", .Battery),
		               Sensor("DeviceMotion", .DeviceMotion),
		               //Sensor("EddystoneProximity", .EddystoneProximity),
		               Sensor("Gyroscope", .Gyroscope),
		               //Sensor("iBeaconProximity", .iBeaconProximity),
		               //Sensor("Location", .Location),
		               Sensor("Magnetometer", .Magnetometer),
		               //Sensor("Microphone", .Microphone),
		               Sensor("MotionActivity", .MotionActivity),
		               Sensor("Pedometer",.Pedometer)
			]
		for sensor in sensorList {
			if sensingKit?.isSensorAvailable(sensor.type) ?? false {
				do {
					try sensingKit?.register(sensor.type)
					sensors[sensor.type] = sensor
				} catch {
					debugPrint(error)
				}
			}
		}
	}

	private func iterateEnum<T: Hashable>(_: T.Type) -> AnyIterator<T> {
		var i = 0
		return AnyIterator {
			let next = withUnsafeBytes(of: &i) { $0.load(as: T.self) }
			if next.hashValue != i { return nil }
			i += 1
			return next
		}
	}

	@objc(listSensors:)
	func listSensors(command: CDVInvokedUrlCommand) {
		var array: [String] = []
		for sensor in sensors.values  {
			array.append(sensor.name)
		}
		let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK,	messageAs: array)

		commandDelegate!.send(pluginResult, callbackId: command.callbackId)
	}

	@objc(startSensors:)
	func startSensors(command: CDVInvokedUrlCommand) {
		if let selected = command.arguments[0] as? NSArray {
			if let sensingKit = self.sensingKit {
				for sensor in sensors.values  {
					if sensingKit.isSensorSensing(sensor.type) {
						if !selected.contains(sensor.name) {
							do {
								debugPrint("Stopping \(sensor.name)")
								try sensingKit.stopContinuousSensing(with: sensor.type)
								try sensingKit.unsubscribeAllHandlers(from: sensor.type)
							} catch {
								debugPrint(error)
							}
						}
					} else if selected.contains(sensor.name) {
						if let urlBase = command.arguments[1] as? String {
							startSensor(sensor, urlBase: urlBase)
						}
					}
				}
			}
		}

	}

	private func startSensor(_ sensor: Sensor, urlBase: String) {
		do {
			sensor.message = ""
			try sensingKit?.subscribe(to: sensor.type, withHandler: { (sensorType, sensorData, error) in
				if let error = error {
					debugPrint(error)
				} else {
					if sensor.message.isEmpty {
						print("Started delayed request for \(sensor.name)")
						let when = DispatchTime.now() + 2 // seconds
						DispatchQueue.main.asyncAfter(deadline: when) {
							let message = sensor.message
							sensor.message = ""

							var request = URLRequest(url: URL(string: urlBase + "/ui/\(sensor.name)/data")!)
							request.httpMethod = "POST"
							request.httpBody = message.data(using: .utf8)
							request.addValue("close", forHTTPHeaderField: "Connection")
							let task = URLSession.shared.dataTask(with: request) { data, response, error in
								if let error = error {
									print("error=\(error)")
								} else if let httpStatus = response as? HTTPURLResponse, httpStatus.statusCode != 200 {
									print("statusCode should be 200, but is \(httpStatus.statusCode)")
									print("error=\(httpStatus.statusCode): \(HTTPURLResponse.localizedString(forStatusCode: httpStatus.statusCode))")
								}
								if let data = data {
									if let message = String(data: data, encoding: .utf8) {
										print(message)
									}
								}
							}
							task.resume()
						}
					}
					if let data = sensorData {
						var csvColumns = data.csvString.split(separator: ",")
						csvColumns.removeFirst()
						csvColumns[0] = csvColumns[0].replacingOccurrences(of: ".", with: "").dropLast(3)

						sensor.message += csvColumns.joined(separator: ",") + "\n"
					}
				}
			})
			try sensingKit?.startContinuousSensing(with: sensor.type)
		} catch {
			debugPrint(error)
		}
	}

	@objc(stop:)
	func stop(command: CDVInvokedUrlCommand) {
		if let sensingKit = self.sensingKit {
			for sensor in sensors.values  {
				if sensingKit.isSensorSensing(sensor.type) {
					do {
						debugPrint("Stopping \(sensor.name)")
						try sensingKit.stopContinuousSensing(with: sensor.type)
						try sensingKit.unsubscribeAllHandlers(from: sensor.type)
					} catch {
						debugPrint(error)
					}
				}
			}
		}
	}
}
