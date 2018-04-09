import UIKit

let databoxProtocolKey = "DataboxProtocolHandled"
var certs = [] as CFArray
var databoxServerURL = ""

class DataboxURLProtocol: URLProtocol, URLSessionDelegate {
	var requestTask: URLSessionTask?
	lazy var secureSession: URLSession = {
		return URLSession(configuration: URLSessionConfiguration.default, delegate: self, delegateQueue: nil)
	}()

	override class func canInit(with request: URLRequest) -> Bool {
		if let url = request.url {
			if URLProtocol.property(forKey: databoxProtocolKey, in: request) == nil {
				if databoxServerURL.isEmpty {
					if url.scheme?.lowercased() == "https" && url.path.lowercased() == "/api/connect" {
						return true
					}
				} else if url.absoluteString.starts(with: databoxServerURL) {
					return true
				}
			}
		}

		return false
	}

	override class func canonicalRequest(for request: URLRequest) -> URLRequest {
		return request
	}

	override class func requestIsCacheEquivalent(_ a: URLRequest, to b: URLRequest) -> Bool {
		return super.requestIsCacheEquivalent(a, to: b)
	}

	override func startLoading() {
		if CFArrayGetCount(certs) == 0 {
			var components = URLComponents(string: self.request.url!.absoluteString)
			components?.scheme = "http"
			components?.path = "/cert.der"
			if let url = components?.url {
				requestTask = URLSession.shared.dataTask(with: url) { data, response, error in
					if let error = error {
						NSLog("\(error)")
						self.client?.urlProtocol(self, didFailWithError: error)
						self.client?.urlProtocolDidFinishLoading(self)
						return
					}

					guard let data = data else {
						self.client?.urlProtocol(self, didFailWithError: NSError(domain:"", code:404, userInfo:nil))
						self.client?.urlProtocolDidFinishLoading(self)
						return
					}

					let cert = SecCertificateCreateWithData(nil, data as CFData)
					certs = [cert] as CFArray
					if let host = components?.host {
						databoxServerURL = "https://" + host
					}

					self.handleRequest()
				}
				requestTask?.resume()
			} else {
				self.client?.urlProtocol(self, didFailWithError: NSError(domain:"", code:400, userInfo:nil))
				self.client?.urlProtocolDidFinishLoading(self)
			}
		} else {
			handleRequest()
		}
	}

	override func stopLoading() {
		requestTask?.cancel()
		requestTask = nil
	}

	private func handleRequest() {
		let mutableRequest = NSMutableURLRequest.init(url: self.request.url!, cachePolicy:  NSURLRequest.CachePolicy.useProtocolCachePolicy, timeoutInterval: 240.0)
		if let method = self.request.httpMethod {
			mutableRequest.httpMethod = method
		}
		mutableRequest.allHTTPHeaderFields = self.request.allHTTPHeaderFields

		URLProtocol.setProperty("true", forKey: databoxProtocolKey, in: mutableRequest)

		requestTask = self.secureSession.dataTask(with: mutableRequest as URLRequest) { data, response, error in
			if let error = error {
				NSLog("\(error)")
				self.client?.urlProtocol(self, didFailWithError: error)
			}

			if let response = response {
				self.client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
				if let data = data {
					self.client?.urlProtocol(self, didLoad: data)
				}
			}
			self.client?.urlProtocolDidFinishLoading(self)
		}
		requestTask?.resume()
	}

	func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge, completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Swift.Void) {
		if (challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust) {
			if let trust = challenge.protectionSpace.serverTrust {
				SecTrustSetAnchorCertificates(trust, certs)
				SecTrustSetAnchorCertificatesOnly(trust, false)

				completionHandler(URLSession.AuthChallengeDisposition.useCredential, URLCredential(trust: trust))
				return
			}
		}

		// Pinning failed
		completionHandler(URLSession.AuthChallengeDisposition.cancelAuthenticationChallenge, nil)
	}
}
