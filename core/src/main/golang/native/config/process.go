package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/dlclark/regexp2"

	"cfa/native/common"

	"github.com/metacubex/mihomo/common/utils"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
)

var processors = []processor{
	patchExternalController, // must before patchOverride, so we only apply ExternalController in Override settings
	patchOverride,
	patchGeneral,
	patchProfile,
	patchDns,
	patchTun,
	patchListeners,
	patchProxyGroups,
	patchProviders,
	validConfig,
}

type processor func(cfg *config.RawConfig, profileDir string) error

func patchOverride(cfg *config.RawConfig, _ string) error {
	if err := json.NewDecoder(strings.NewReader(ReadOverride(OverrideSlotPersist))).Decode(cfg); err != nil {
		log.Warnln("Apply persist override: %s", err.Error())
	}
	if err := json.NewDecoder(strings.NewReader(ReadOverride(OverrideSlotSession))).Decode(cfg); err != nil {
		log.Warnln("Apply session override: %s", err.Error())
	}

	return nil
}

func patchExternalController(cfg *config.RawConfig, _ string) error {
	cfg.ExternalController = ""
	cfg.ExternalControllerTLS = ""

	return nil
}

func patchGeneral(cfg *config.RawConfig, profileDir string) error {
	cfg.Interface = ""
	cfg.RoutingMark = 0
	if cfg.ExternalController != "" || cfg.ExternalControllerTLS != "" {
		cfg.ExternalUI = profileDir + "/ui"
	}

	return nil
}

func patchProfile(cfg *config.RawConfig, _ string) error {
	cfg.Profile.StoreSelected = false
	cfg.Profile.StoreFakeIP = true

	return nil
}

func patchDns(cfg *config.RawConfig, _ string) error {
	if !cfg.DNS.Enable {
		cfg.DNS = config.DefaultRawConfig().DNS
		cfg.DNS.Enable = true
		cfg.DNS.NameServer = defaultNameServers
		cfg.DNS.EnhancedMode = C.DNSFakeIP
		cfg.DNS.FakeIPRange = defaultFakeIPRange
		cfg.DNS.FakeIPFilter = defaultFakeIPFilter

		cfg.ClashForAndroid.AppendSystemDNS = true
	}

	if cfg.ClashForAndroid.AppendSystemDNS {
		cfg.DNS.NameServer = append(cfg.DNS.NameServer, "system://")
	}

	return nil
}

func patchTun(cfg *config.RawConfig, _ string) error {
	cfg.Tun.Enable = false
	cfg.Tun.AutoRoute = false
	cfg.Tun.AutoDetectInterface = false
	return nil
}

func patchListeners(cfg *config.RawConfig, _ string) error {
	newListeners := make([]map[string]any, 0, len(cfg.Listeners))
	for _, mapping := range cfg.Listeners {
		if proxyType, existType := mapping["type"].(string); existType {
			switch proxyType {
			case "tproxy", "redir", "tun":
				continue // remove those listeners which is not supported
			}
		}
		newListeners = append(newListeners, mapping)
	}
	cfg.Listeners = newListeners
	return nil
}

// mihomo removed `type: relay` groups upstream: ParseProxyGroup fails the
// whole load with "please using dialer-proxy instead". Downgrade such groups
// to a selector so their name keeps resolving for the rules, other groups,
// dialer-proxy chains and tunnels that reference it — dropping the group
// outright would cascade into fatal "proxy [X] not found" errors elsewhere.
// Chain behavior is lost, but the profile loads.
//
// After the downgrade the reader's selection page gets the steering groups
// injected: subscriptions differ wildly in what they nest under the selection
// page - the screening template carries them, but provider subscriptions
// routinely define Auto / Fallback / the video group and leave them out of
// the selector, so the reader cannot reach any of them from the one page
// they steer from. Groups that exist but sit outside the selector are added
// to its front, in the order the reader steers in, so the choice the reader
// makes by hand covers the whole set rather than bare nodes alone.
func patchProxyGroups(cfg *config.RawConfig, _ string) error {
	for _, group := range cfg.ProxyGroup {
		groupType, _ := group["type"].(string)
		if groupType != "relay" {
			continue
		}
		name, _ := group["name"].(string)
		group["type"] = "select"
		log.Warnln("proxy group [%s]: relay was removed in mihomo, downgraded to select (chain behavior lost)", name)
	}

	injectSteeringGroups(cfg)

	return nil
}

// selectorMarkers name the group the reader steers from. Both languages a
// subscription is likely to arrive in are matched on the name alone, the same
// markers the interface pins the tab with.
var selectorMarkers = []string{"节点选择", "手动切换", "手动选择"}

// steeringSpecs list the steering groups in the order the reader expects them,
// each by the family of names a subscription is likely to give it. Latin
// markers are matched case-insensitively; a name claimed by an earlier spec
// is not claimed again by a later one.
var steeringSpecs = [][]string{
	{"自动选择", "auto"},
	{"故障转移", "故障切换", "fallback"},
	{"负载均衡", "负荷均衡", "loadbalance", "load balance", "load-balance"},
	{"油管", "youtube"},
}

// injectSteeringGroups fronts the selection page with the steering groups.
//
// Only what the configuration already defines is moved: the reader gains
// reach, nothing is invented behind their back. A steering group that already
// sits in the list stays where the subscription wrote it unless it is out of
// order with one that was missing — the fixed order of this pass is what the
// reader asked for, so every steering group the selector gains is written at
// the front in the order above, and the rest of the page keeps its own order.
//
// The injection double checks for cycles and declines to close one: a
// steering group that references the selector directly or through any chain
// of other groups is left out, since reaching back into the page that reaches
// out to it is a loop mihomo refuses to load, and one broken profile costs
// more than one missing row ever saves.
func injectSteeringGroups(cfg *config.RawConfig) {
	if len(cfg.ProxyGroup) == 0 {
		return
	}

	byName := make(map[string]map[string]any, len(cfg.ProxyGroup))
	for _, group := range cfg.ProxyGroup {
		name, _ := group["name"].(string)
		if name != "" {
			byName[name] = group
		}
	}

	// Claim the steering groups by the first spec that fits, so a group
	// matching two of them lands at the higher slot exactly once.
	steering := make([]string, 0, len(steeringSpecs))
	claimed := make(map[string]bool, len(cfg.ProxyGroup))
	for _, spec := range steeringSpecs {
		for _, group := range cfg.ProxyGroup {
			name, _ := group["name"].(string)
			if name == "" || claimed[name] {
				continue
			}
			lower := strings.ToLower(name)
			for _, marker := range spec {
				if strings.Contains(lower, marker) {
					steering = append(steering, name)
					claimed[name] = true
					break
				}
			}
		}
	}
	if len(steering) == 0 {
		return
	}

	for _, group := range cfg.ProxyGroup {
		name, _ := group["name"].(string)
		if !isReaderSelector(group, name) {
			continue
		}
		members, ok := group["proxies"].([]any)
		if !ok || len(members) == 0 {
			continue
		}
		has := func(member string) bool {
			for _, entry := range members {
				if s, _ := entry.(string); s == member {
					return true
				}
			}
			return false
		}
		inject := make([]any, 0, len(steering))
		for _, target := range steering {
			if target == name || has(target) || references(byName, target, name) {
				continue
			}
			inject = append(inject, target)
		}
		if len(inject) == 0 {
			continue
		}
		group["proxies"] = append(inject, members...)
		log.Infoln("proxy group [%s]: steering groups injected ahead of %d members", name, len(members))
	}
}

func isReaderSelector(group map[string]any, name string) bool {
	groupType, _ := group["type"].(string)
	if !strings.EqualFold(groupType, "select") {
		return false
	}
	for _, marker := range selectorMarkers {
		if strings.Contains(strings.ToLower(name), marker) {
			return true
		}
	}
	return false
}

// references reports whether [from] reaches [to] through the proxy-group
// graph, directly or along any chain of nested groups.
func references(byName map[string]map[string]any, from, to string) bool {
	seen := map[string]bool{from: true}
	stack := []string{from}
	for len(stack) > 0 {
		current := stack[len(stack)-1]
		stack = stack[:len(stack)-1]
		if current == to {
			return true
		}
		group := byName[current]
		if group == nil {
			continue
		}
		members, _ := group["proxies"].([]any)
		for _, entry := range members {
			member, _ := entry.(string)
			if member == "" || seen[member] {
				continue
			}
			if member == to {
				return true
			}
			if _, isGroup := byName[member]; isGroup {
				seen[member] = true
				stack = append(stack, member)
			}
		}
	}
	return false
}

func patchProviders(cfg *config.RawConfig, profileDir string) error {
	forEachProviders(cfg, func(index int, total int, key string, provider map[string]any, prefix string) {
		path, _ := provider["path"].(string)
		if len(path) > 0 {
			path = common.ResolveAsRoot(path)
		} else if url, ok := provider["url"].(string); ok {
			path = prefix + "/" + utils.MakeHash([]byte(url)).String() // same as C.GetPathByHash
		} else {
			return // both path and url are empty, maybe inline provider
		}
		provider["path"] = profileDir + "/providers/" + path
	})

	return nil
}

func validConfig(cfg *config.RawConfig, _ string) error {
	if len(cfg.Proxy) == 0 && len(cfg.ProxyProvider) == 0 {
		return errors.New("profile does not contain `proxies` or `proxy-providers`")
	}

	if _, err := regexp2.Compile(cfg.ClashForAndroid.UiSubtitlePattern, 0); err != nil {
		return fmt.Errorf("compile ui-subtitle-pattern: %s", err.Error())
	}

	return nil
}

func process(cfg *config.RawConfig, profileDir string) error {
	for _, p := range processors {
		if err := p(cfg, profileDir); err != nil {
			return err
		}
	}

	return nil
}
