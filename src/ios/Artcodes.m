/********* Artcodes.m Cordova Plugin Implementation *******/

#import <Cordova/CDV.h>
#import <ArtcodesScanner/ArtcodesScanner-Swift.h>

@interface Artcodes : CDVPlugin {
  // Member variables go here.
}

- (void)scanArtcode:(CDVInvokedUrlCommand*)command;
@end

@implementation Artcodes

- (void)scanArtcode:(CDVInvokedUrlCommand*)command
{
	NSDictionary* experience = [command.arguments objectAtIndex:0];
	ScannerViewController* scanViewController = [ScannerViewController scanner:experience closure:^(NSString* code) {
		CDVPluginResult* pluginResult = nil;
		if (code != nil && [code length] > 0)
		{
			pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:code];
		}
		else
		{
			pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR];
		}

		[self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        dispatch_async(dispatch_get_main_queue(), ^{
    		[self.viewController dismissViewControllerAnimated:true completion:nil];
        });
    }];
	[self.viewController presentViewController:scanViewController animated:YES completion:nil];
}

@end
