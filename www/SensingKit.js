module.exports = {
	start: function(url, callback) {
		cordova.exec(callback, function (err) {
			callback('Error: ' + err);
		}, "SensingKit", "start", [url]);
	},

	listSensors: function (callback) {
		cordova.exec(callback, function (err) {
			callback('Error: ' + err);
		}, "SensingKit", "listSensors");
	}
};