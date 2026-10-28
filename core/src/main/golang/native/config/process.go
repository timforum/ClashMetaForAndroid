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

	dropUnresolvableMembers(cfg)
	injectSteeringGroups(cfg)
	injectVideoSpeedGroups(cfg)
	orderProxyGroups(cfg)

	return nil
}

// dropUnresolvableMembers clears the group members nothing in the
// configuration answers for, instead of letting one of them fail the load.
//
// The embedded mihomo resolves a group's members against what it has parsed
// so far, so a member naming a group the subscription never declares (or
// declares later — it does not carry the newer readers' forward references)
// kills the whole profile with "'X' not found", which readers see as a
// failed subscription update rather than as a typo a provider made. Every
// provider feed carries some of these; one dead row must not take the
// tunnel down. Members the loader cannot answer for are dropped here, a
// group's own name among them (a selector listing itself is a cycle the
// loader would refuse), and a group left with nothing on the list is given
// DIRECT so it keeps resolving for the rules and groups that name it —
// worse than the member that was there, better than a page the reader
// cannot load at all.
func dropUnresolvableMembers(cfg *config.RawConfig) {
	if len(cfg.ProxyGroup) == 0 {
		return
	}

	known := map[string]bool{
		"DIRECT": true,
		"REJECT": true,
		"REJECT-DROP": true,
		"PASS":   true,
		"COMPATIBLE": true,
	}
	for _, proxy := range cfg.Proxy {
		name, _ := proxy["name"].(string)
		if name != "" {
			known[name] = true
		}
	}
	for _, group := range cfg.ProxyGroup {
		name, _ := group["name"].(string)
		if name != "" {
			known[name] = true
		}
	}

	for _, group := range cfg.ProxyGroup {
		name, _ := group["name"].(string)
		members, ok := group["proxies"].([]any)
		if !ok {
			continue
		}
		kept := make([]any, 0, len(members))
		for _, entry := range members {
			member, _ := entry.(string)
			if member == name {
				log.Warnln("proxy group [%s]: dropped self reference", name)
				continue
			}
			if !known[member] {
				log.Warnln("proxy group [%s]: dropped unresolvable member [%s]", name, member)
				continue
			}
			kept = append(kept, entry)
		}
		if len(kept) == 0 {
			log.Warnln("proxy group [%s]: every member dropped, giving it DIRECT", name)
			kept = append(kept, "DIRECT")
		}
		group["proxies"] = kept
	}
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

// speedGroupSpecs name the provider's own speed groups the reader wants inside
// the video group, each by a family of names, the auto variant asked first.
// The stem 速组 covers both of the pair the provider ships today; a future
// sibling is claimed by the same family because it belongs to the same race.
var speedGroupSpecs = [][]string{
	{"速组·自动", "速组自动"},
	{"速组"},
}

// videoMarkers name the group whose members carry YouTube; the same markers
// the interface uses to find the video page.
var videoMarkers = []string{"油管", "youtube"}

func isVideoGroup(name string) bool {
	lower := strings.ToLower(name)
	for _, marker := range videoMarkers {
		if strings.Contains(lower, marker) {
			return true
		}
	}
	return false
}

// injectVideoSpeedGroups fronts the video group with the provider's speed
// groups.
//
// The video group is where YouTube's rules land, and the reader wants the
// speed family reachable from it: the same reach the selection page got from
// the steering injection, pointed at the one page whose node the video stream
// actually rides. Only what the configuration already defines is moved, the
// recipient is a select group (an url-test the provider wired themselves is
// their call, not ours), a member already there is not added twice, and the
// injection keeps the cycle guard: a speed group that reaches back into the
// video group through any chain is left out, since mihomo refuses to load a
// loop and one broken profile costs more than one missing row ever saves.
func injectVideoSpeedGroups(cfg *config.RawConfig) {
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

	// Claim the speed groups by the first spec that fits, the auto variant
	// ahead of the plain one, so each lands once in the user's order.
	speeds := make([]string, 0, len(speedGroupSpecs))
	claimed := make(map[string]bool, len(cfg.ProxyGroup))
	for _, spec := range speedGroupSpecs {
		for _, group := range cfg.ProxyGroup {
			name, _ := group["name"].(string)
			if name == "" || claimed[name] {
				continue
			}
			for _, marker := range spec {
				if strings.Contains(name, marker) {
					speeds = append(speeds, name)
					claimed[name] = true
					break
				}
			}
		}
	}
	if len(speeds) == 0 {
		return
	}

	for _, group := range cfg.ProxyGroup {
		name, _ := group["name"].(string)
		if !isVideoGroup(name) {
			continue
		}
		groupType, _ := group["type"].(string)
		if groupType != "select" {
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
		inject := make([]any, 0, len(speeds))
		for _, target := range speeds {
			if target == name || has(target) || references(byName, target, name) {
				continue
			}
			inject = append(inject, target)
		}
		if len(inject) == 0 {
			continue
		}
		group["proxies"] = append(inject, members...)
		log.Infoln("proxy group [%s]: speed groups injected ahead of %d members", name, len(members))
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
	return reaches(byName, from, to)
}

func reaches(byName map[string]map[string]any, from, to string) bool {
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

// orderProxyGroups puts every group ahead of the groups that name it.
//
// The embedded mihomo resolves a group's members while it parses, one group
// at a time, so a member naming a group the list defines later carries the
// same "'X' not found" death as a member naming nothing at all — drops only
// mask that; subscriptions legitimately nest a selection page several groups
// ahead of the steering groups it lists. The groups are therefore re-emitted
// dependencies first, every referenced group before its referrer, keeping the
// subscription's own relative order wherever no reference constrains it (a
// stable depth-first walk over the original list). A true cycle - groups
// naming each other, which no reordering can unroll - has the closing member
// dropped instead: the referrer loses one row it could not have kept anyway,
// and the profile still loads.
func orderProxyGroups(cfg *config.RawConfig) {
	if len(cfg.ProxyGroup) < 2 {
		return
	}

	byName := make(map[string]map[string]any, len(cfg.ProxyGroup))
	for _, group := range cfg.ProxyGroup {
		name, _ := group["name"].(string)
		if name != "" {
			byName[name] = group
		}
	}

	const (
		unseen = iota
		opened
		done
	)
	state := make(map[string]int, len(cfg.ProxyGroup))
	ordered := make([]map[string]any, 0, len(cfg.ProxyGroup))

	var visit func(group map[string]any, name string)
	visit = func(group map[string]any, name string) {
		state[name] = opened

		members, _ := group["proxies"].([]any)
		kept := make([]any, 0, len(members))
		for _, entry := range members {
			member, _ := entry.(string)
			if member == name || state[member] == opened {
				// A back edge is the closing side of a cycle: no order can
				// put the group before the group it is inside of, so the
				// member goes and the rest of the list keeps loading.
				log.Warnln("proxy group [%s]: dropped cycle closing member [%s]", name, member)
				continue
			}
			kept = append(kept, entry)
			if memberGroup, isGroup := byName[member]; isGroup && state[member] == unseen {
				visit(memberGroup, member)
			}
		}
		group["proxies"] = kept

		state[name] = done
		ordered = append(ordered, group)
	}

	for _, group := range cfg.ProxyGroup {
		name, _ := group["name"].(string)
		if name != "" && state[name] == unseen {
			visit(group, name)
		}
	}

	cfg.ProxyGroup = ordered
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
