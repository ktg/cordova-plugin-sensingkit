module.exports = {
	stop: function (callback) {
		cordova.exec(callback, function (err) {
			callback('Error: ' + err);
		}, "SensingKit", "stop");
	},

	listSensors: function (callback) {
		cordova.exec(callback, function (err) {
			callback('Error: ' + err);
		}, "SensingKit", "listSensors");
	},

	startSensors: function (sensors, url, callback) {
		localStorage.setItem('sensors', JSON.stringify(sensors));
		cordova.exec(callback, function (err) {
			callback('Error: ' + err);
		}, "SensingKit", "startSensors", [sensors, url]);
	}
};
