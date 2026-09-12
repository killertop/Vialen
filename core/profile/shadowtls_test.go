package profile

import "testing"

func TestShadowTLSValidationAndExplicitURIUnsupported(t *testing.T) {
	for _, version := range []uint32{1, 2, 3} {
		p := Profile{Type: "shadowtls", Server: "example.com", Port: 443, TLS: &TLS{Enabled: true}, ShadowTLS: &ShadowTLS{Version: version, Password: "secret"}}
		if err := Validate(p); err != nil {
			t.Fatal(err)
		}
		if _, err := ExportURI(p); err == nil {
			t.Fatal("ShadowTLS has no standard URI")
		}
		p.ShadowTLS.Password = ""
		if err := Validate(p); (err == nil) != (version == 1) {
			t.Fatalf("version %d password validation: %v", version, err)
		}
	}
	p := Profile{Type: "shadowtls", Server: "example.com", Port: 443, TLS: &TLS{Enabled: true}, ShadowTLS: &ShadowTLS{Version: 3, Password: "secret"}}
	for _, mutate := range []func(*Profile){
		func(p *Profile) { p.ShadowTLS = &ShadowTLS{Version: 4, Password: "secret"} },
		func(p *Profile) { p.TLS = nil },
		func(p *Profile) { p.Transport = &Transport{Type: "ws"} },
		func(p *Profile) { p.AnyTLS = &AnyTLS{Password: "secret"} },
		func(p *Profile) { p.TLS = &TLS{Enabled: true, Reality: &Reality{PublicKey: "key"}} },
	} {
		q := p
		mutate(&q)
		if Validate(q) == nil {
			t.Fatal("invalid ShadowTLS accepted")
		}
	}
}
