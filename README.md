# Cordova Hello World Plugin

A simple plugin  from scanning Artcodes.


## Using
Install the plugin

    $ ionic plugin add https://github.com/horizon-institute/artcodes-cordova.git


To use

```js
	Artcodes.scan({name: "Test Experience", actions:[{codes:["1:1:3:3:4"]}]}, function(marker) {
		alert(marker);
	});
```

To get it running in iOS, the ArtcodesScanner and SwiftyJSON frameworks must both to added to the embedded frameworks.
 - Open platforms/ios/<projectname>.xcodeproj in xcode
 - In the tree on the left, select the root node, with the project name
 - In the general tab, scroll down to 'Embedded Binaries'
 - Using the +, add ArtcodesScanner.framework and SwiftyJSON.framework to Embedded Binaries.
 - Remove duplicates from 'Linked Frameworks and Libraries'
 - May also need to set the Build Setting 'Always Embed Swift Standard Libraries' to 'Yes' 
