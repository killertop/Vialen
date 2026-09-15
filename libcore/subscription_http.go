package libcore

import (
	"context"
	"crypto/x509"
	"errors"
	"fmt"
	"net"
)

type httpStatusError int

func (e httpStatusError) Error() string { return fmt.Sprintf("HTTP status %d", int(e)) }

// Only successful responses carry payload/headers. Failures expose stable codes,
// never URLs, credentials, server bodies or raw transport exception strings.
type SubscriptionHTTPResult struct {
	Code        string
	Content     []byte
	Userinfo    string
	Disposition string
}

func subscriptionHTTPCode(err error) string {
	if err == nil {
		return "OK"
	}
	if errors.Is(err, context.Canceled) {
		return "CANCELLED"
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return "TIMEOUT"
	}
	if errors.Is(err, errResponseTooLarge) {
		return "TOO_LARGE"
	}
	var status httpStatusError
	if errors.As(err, &status) {
		switch {
		case status == 408:
			return "TIMEOUT"
		case status == 429:
			return "RATE_LIMITED"
		case status == 425 || status >= 500:
			return "SERVER_ERROR"
		case status == 401 || status == 403:
			return "ACCESS_DENIED"
		case status == 404 || status == 410:
			return "NOT_FOUND"
		default:
			return "HTTP_REJECTED"
		}
	}
	var unknown x509.UnknownAuthorityError
	var invalid x509.CertificateInvalidError
	var hostname x509.HostnameError
	if errors.As(err, &unknown) || errors.As(err, &invalid) || errors.As(err, &hostname) {
		return "TLS_REJECTED"
	}
	var dnsError *net.DNSError
	if errors.As(err, &dnsError) {
		return "DNS_FAILED"
	}
	var networkError net.Error
	if errors.As(err, &networkError) && networkError.Timeout() {
		return "TIMEOUT"
	}
	return "NETWORK_ERROR"
}

func (r *httpRequest) ExecuteSubscription() *SubscriptionHTTPResult {
	response, err := r.execute()
	if err != nil {
		return &SubscriptionHTTPResult{Code: subscriptionHTTPCode(err)}
	}
	defer response.Close()
	content, err := response.GetContent()
	if err != nil {
		return &SubscriptionHTTPResult{Code: subscriptionHTTPCode(err)}
	}
	h := response.(*httpResponse)
	return &SubscriptionHTTPResult{Code: "OK", Content: content,
		Userinfo: h.Header.Get("Subscription-Userinfo"), Disposition: h.Header.Get("Content-Disposition")}
}
