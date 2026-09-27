package tun

import (
	"errors"
	"net"

	D "github.com/miekg/dns"
)

func shouldHijackDns(dns net.IP, target net.IP, targetPort int) bool {
	if targetPort != 53 {
		return false
	}

	return net.IPv4zero.Equal(dns) || target.Equal(dns)
}

// errDnsRelayUnsupported is returned because mihomo removed the mechanism this
// relay depended on.
//
// The previous implementation called dns.ServeDNSWithDefaultServer, which lived in
// an `android && cmfa` patch file. It forwarded to an `isolateHandler` that was only
// ever populated by dns.UpdateIsolateHandler, which this app never called, so
// isolateHandler was always nil and ServeDNSWithDefaultServer always returned
// D.ErrTime. The hijacked-DNS relay therefore never resolved anything to begin with.
//
// The new core keeps dns.FlushCacheWithDefaultResolver and dns.UpdateSystemDNS in
// dns/patch_android.go but drops ServeDNSWithDefaultServer, the isolate handler, and
// UpdateIsolateHandler. The replacement would be to keep a *dns.Resolver built from
// the system DNS list and call ExchangeContext on it, but that turns a
// never-succeeding path into a live one, which is a behaviour change on the DNS hot
// path and needs device testing. Left failing for now on purpose.
var errDnsRelayUnsupported = errors.New("dns relay unsupported: mihomo removed ServeDNSWithDefaultServer")

func relayDns(payload []byte) ([]byte, error) {
	msg := &D.Msg{}
	if err := msg.Unpack(payload); err != nil {
		return nil, err
	}

	return nil, errDnsRelayUnsupported
}
