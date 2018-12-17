/*
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 */

//
//  AppDelegate.m
//  databox
//
//  Created by ___FULLUSERNAME___ on ___DATE___.
//  Copyright ___ORGANIZATIONNAME___ ___YEAR___. All rights reserved.
//

#import "AppDelegate.h"
#import "MainViewController.h"

@implementation AppDelegate

- (BOOL)application:(UIApplication*)application didFinishLaunchingWithOptions:(NSDictionary*)launchOptions
{
    self.viewController = [[MainViewController alloc] init];
	
	[self loadHTTPCookies];
	
	return [super application:application didFinishLaunchingWithOptions:launchOptions];
}

- (void)applicationDidEnterBackground:(UIApplication *)application
{
	[self saveHTTPCookies];
}

- (void)applicationWillEnterForeground:(UIApplication *)application
{
	[self loadHTTPCookies];
}

- (void)applicationWillTerminate:(UIApplication *)application
{
	//Other existing code
	[self saveHTTPCookies];
}

-(void)loadHTTPCookies
{
	NSMutableArray* cookieDictionary = [[NSUserDefaults standardUserDefaults] valueForKey:@"cookieArray"];
	
	for (int i=0; i < cookieDictionary.count; i++)
	{
		NSMutableDictionary* cookieDictionary1 = [[NSUserDefaults standardUserDefaults] valueForKey:[cookieDictionary objectAtIndex:i]];
		NSHTTPCookie *cookie = [NSHTTPCookie cookieWithProperties:cookieDictionary1];
		[[NSHTTPCookieStorage sharedHTTPCookieStorage] setCookie:cookie];
	}
}

-(void)saveHTTPCookies
{
	NSMutableArray *cookieArray = [[NSMutableArray alloc] init];
	for (NSHTTPCookie *cookie in [[NSHTTPCookieStorage sharedHTTPCookieStorage] cookies]) {
		[cookieArray addObject:cookie.name];
		NSMutableDictionary *cookieProperties = [NSMutableDictionary dictionary];
		[cookieProperties setObject:cookie.name forKey:NSHTTPCookieName];
		[cookieProperties setObject:cookie.value forKey:NSHTTPCookieValue];
		[cookieProperties setObject:cookie.domain forKey:NSHTTPCookieDomain];
		[cookieProperties setObject:cookie.path forKey:NSHTTPCookiePath];
		[cookieProperties setObject:[NSNumber numberWithUnsignedInteger:cookie.version] forKey:NSHTTPCookieVersion];
		[cookieProperties setObject:[[NSDate date] dateByAddingTimeInterval:2629743] forKey:NSHTTPCookieExpires];
		
		[[NSUserDefaults standardUserDefaults] setValue:cookieProperties forKey:cookie.name];
		[[NSUserDefaults standardUserDefaults] synchronize];
	}
	
	[[NSUserDefaults standardUserDefaults] setValue:cookieArray forKey:@"cookieArray"];
	[[NSUserDefaults standardUserDefaults] synchronize];
}

@end
