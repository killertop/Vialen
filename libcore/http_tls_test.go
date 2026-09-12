package libcore

import (
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestModernTLSNegotiation(t *testing.T) {
	for _, version := range []uint16{tls.VersionTLS11, tls.VersionTLS12, tls.VersionTLS13} {
		t.Run(fmt.Sprint(version), func(t *testing.T) {
			server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.TLS.Version != version {
					t.Errorf("negotiated %x, want %x", r.TLS.Version, version)
				}
				w.Write([]byte("subscription"))
			}))
			server.TLS = &tls.Config{MinVersion: version, MaxVersion: version}
			server.StartTLS()
			defer server.Close()
			client := NewHttpClient().(*httpClient)
			defer client.Close()
			client.PinnedTLS12()
			client.ModernTLS()
			client.tls.RootCAs = x509.NewCertPool()
			client.tls.RootCAs.AddCert(server.Certificate())
			request := client.NewRequest()
			if err := request.SetURL(server.URL); err != nil {
				t.Fatal(err)
			}
			response, err := request.Execute()
			if version == tls.VersionTLS11 {
				if err == nil {
					response.GetContent()
					t.Fatal("TLS 1.1 must be rejected")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if _, err := response.GetContent(); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestModernTLSCertificateFailureDoesNotDisableVerification(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("subscription"))
	}))
	defer server.Close()
	client := NewHttpClient().(*httpClient)
	defer client.Close()
	client.ModernTLS()
	client.tls.RootCAs = x509.NewCertPool() // Deliberately do not trust the test certificate.
	for attempt := 0; attempt < 2; attempt++ {
		request := client.NewRequest()
		if err := request.SetURL(server.URL); err != nil {
			t.Fatal(err)
		}
		response, err := request.Execute()
		if err == nil {
			response.GetContent()
			t.Fatal("untrusted certificate accepted")
		}
		if client.tls.InsecureSkipVerify {
			t.Fatal("certificate failure disabled verification")
		}
	}
	request := client.NewRequest()
	request.AllowInsecure() // Existing explicit user override remains supported.
	if err := request.SetURL(server.URL); err != nil {
		t.Fatal(err)
	}
	response, err := request.Execute()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := response.GetContent(); err != nil {
		t.Fatal(err)
	}
}
