module.exports = {
	start: function(url, callback) {
		cordova.exec(callback, function (err) {
			callback('Error: ' + err);
		}, "SensingKit", "start", [url]);
	},

	isRunning: function(callback) {
		cordova.exec(callback, function (err) {
			callback('Error: ' + err);
		}, "SensingKit", "isRunning");
	},

	stop: function (callback) {
		cordova.exec(callback, function (err) {
			callback('Error: ' + err);
		}, "SensingKit", "stop");
	}
};