package tunnel

import (
	"fmt"
	"sort"
	"strings"

	"github.com/dlclark/regexp2"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

type SortMode int

const (
	Default SortMode = iota
	Title
	Delay
)

type Proxy struct {
	Name     string `json:"name"`
	Title    string `json:"title"`
	Subtitle string `json:"subtitle"`
	Type     string `json:"type"`
	Delay    int    `json:"delay"`
	IsGroup  bool   `json:"isGroup"`
}

type ProxyGroup struct {
	Type    string   `json:"type"`
	Now     string   `json:"now"`
	// InUse names the leaf proxy the connection is on behind this group: a
	// group whose members are other groups routes through the member it
	// selected, so the node in use is found by following that selection down
	// until a leaf is reached. Now keeps naming the member the group itself
	// picked, which is the pair the UI shows and the watcher acts on.
	InUse   string   `json:"inUse"`
	Proxies []*Proxy `json:"proxies"`
}

// isPolicyGroup reports whether p is one of the "just pick something" groups.
// They are named after what they do rather than after a provider, and they
// carry no delay of their own, so neither title nor delay ordering says
// anything useful about them.
func isPolicyGroup(p *Proxy) bool {
	switch p.Type {
	case C.URLTest.String(), C.LoadBalance.String(), C.Fallback.String():
		return true
	default:
		return false
	}
}

// sortProxyList sorts list by less, but keeps the policy groups on top in the
// order the group declared them. They are the entries a user reaches for when
// they do not want to think, and burying them mid-list - or reordering them
// against each other by a name that means nothing - turns the most common
// choice into a hunt.
func sortProxyList(list []*Proxy, less func(a, b *Proxy) bool) {
	pinned := make([]*Proxy, 0, len(list))
	rest := make([]*Proxy, 0, len(list))

	for _, p := range list {
		if isPolicyGroup(p) {
			pinned = append(pinned, p)
		} else {
			rest = append(rest, p)
		}
	}

	sort.SliceStable(rest, func(i, j int) bool {
		return less(rest[i], rest[j])
	})

	copy(list, pinned)
	copy(list[len(pinned):], rest)
}

func QueryProxyGroupNames(excludeNotSelectable bool) []string {
	mode := tunnel.Mode()

	if mode == tunnel.Direct {
		return []string{}
	}

	global := tunnel.Proxies()["GLOBAL"].Adapter().(outboundgroup.ProxyGroup)
	proxies := global.Providers()[0].Proxies()
	result := make([]string, 0, len(proxies)+1)

	if mode == tunnel.Global {
		result = append(result, "GLOBAL")
	}

	for _, p := range proxies {
		if g, ok := p.Adapter().(outboundgroup.ProxyGroup); ok {
			if !excludeNotSelectable || p.Type() == C.Selector {
				if g.Hidden() {
					continue
				}
				result = append(result, p.Name())
			}
		}
	}

	return result
}

func QueryProxyGroup(name string, sortMode SortMode, uiSubtitlePattern *regexp2.Regexp) *ProxyGroup {
	p := tunnel.Proxies()[name]

	if p == nil {
		log.Warnln("Query group `%s`: not found", name)

		return nil
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Query group `%s`: invalid type %s", name, p.Type().String())

		return nil
	}

	proxies := convertProxies(g.Proxies(), uiSubtitlePattern)
	// 	proxies := collectProviders(g.Providers(), uiSubtitlePattern)

	switch sortMode {
	case Title:
		sortProxyList(proxies, func(a, b *Proxy) bool {
			return strings.Compare(a.Title, b.Title) < 0
		})
	case Delay:
		sortProxyList(proxies, func(a, b *Proxy) bool {
			return a.Delay < b.Delay
		})
	case Default:
	default:
	}

	return &ProxyGroup{
		Type:    g.Type().String(),
		Now:     g.Now(),
		InUse:   InUseNode(name),
		Proxies: proxies,
	}
}

func PatchSelector(selector, name string) bool {
	p := tunnel.Proxies()[selector]

	if p == nil {
		log.Warnln("Patch selector `%s`: not found", selector)

		return false
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Patch selector `%s`: invalid type %s", selector, p.Type().String())

		return false
	}

	s, ok := g.(outboundgroup.SelectAble)
	if !ok {
		log.Warnln("Patch selector `%s`: invalid type %s", selector, p.Type().String())

		return false
	}

	if err := s.Set(name); err != nil {
		log.Warnln("Patch selector `%s`: %s", selector, err.Error())
	}

	log.Infoln("Patch selector %s -> %s", selector, name)

	closeConnByGroup(selector)

	return true
}

// SpeedTarget is one live group member handed to the real-transfer speed
// test: the name results are reported under, the delay the UI already shows
// so a reader can line the two numbers up, the URL that delay was measured
// against so a fresh measurement lands in the same history the UI reads, the
// adapter the measurement runs through, and the group a selection may be
// written to for it (Owner, empty when no group along the way can hold one).
type SpeedTarget struct {
	Name  string
	Delay int
	Owner string
	// Path names the groups on the way from the group that was asked about
	// down to the direct parent of this leaf. Set only accepts a member, so a
	// node two levels down cannot be reached in one patch: the caller walks
	// this path and moves every group on it, which is what makes a failover
	// actually reach a node living behind a sub group.
	Path    []string
	TestURL string
	Proxy   C.Proxy
}

// GroupSpeedTargets resolves a group to the nodes a speed test may measure.
//
// Nested groups are walked into rather than skipped: a top level group that
// only holds other groups still has real exits behind it, and refusing to
// measure them is what leaves such a group with nothing to rank and nothing to
// switch to. Every leaf carries the group a selection may be written to for
// it, which is the direct parent when that parent can hold a selection at all
// (a selector, a urltest and a fallback answer Set with a member name, a
// loadbalance picks on its own and answers nothing), so the caller patches the
// innermost group that actually owns the node rather than the top one that
// merely routes to it.
//
// The built-in DIRECT/REJECT entries are skipped because they carry no exit:
// timing them would measure the device's own link instead of a node a reader
// could pick. A missing or non-group name is an error so a toggle left behind
// for a removed group fails loudly instead of silently measuring nothing.
func GroupSpeedTargets(name string) ([]SpeedTarget, error) {
	p := tunnel.Proxies()[name]
	if p == nil {
		return nil, fmt.Errorf("group `%s` not found", name)
	}

	if _, ok := p.Adapter().(outboundgroup.ProxyGroup); !ok {
		return nil, fmt.Errorf("`%s` is not a group (%s)", name, p.Type().String())
	}

	result := make([]SpeedTarget, 0, 64)

	var walk func(px C.Proxy, owner string, path []string, depth int)
	walk = func(px C.Proxy, owner string, path []string, depth int) {
		if depth > maxGroupDepth {
			return
		}

		g, isGroup := px.Adapter().(outboundgroup.ProxyGroup)
		if isGroup {
			// A group that can hold a selection owns the leaves directly under
			// it, which is the group the caller patches: Set names a member, so
			// a leaf of an inner group can never be chosen through the outer one.
			inner := ""
			if _, ok := g.(outboundgroup.SelectAble); ok {
				inner = px.Name()
			}

			// The full slice expression forces the copy: appending in place
			// would let the next sibling's group name land inside a path a leaf
			// is already holding.
			subPath := append(path[:len(path):len(path)], px.Name())

			for _, sub := range g.Proxies() {
				walk(sub, inner, subPath, depth+1)
			}

			return
		}

		switch px.Name() {
		case "DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE", "GLOBAL":
			return
		}

		testURL := "https://www.gstatic.com/generate_204"
		for k := range px.ExtraDelayHistories() {
			if len(k) > 0 {
				testURL = k
				break
			}
		}

		result = append(result, SpeedTarget{
			Name:    px.Name(),
			Delay:   int(px.LastDelayForTestUrl(testURL)),
			Owner:   owner,
			Path:    path,
			TestURL: testURL,
			Proxy:   px,
		})
	}

	walk(p, "", nil, 0)

	return result, nil
}

// maxGroupDepth bounds how far a group may nest behind other groups. Real
// subscriptions nest a couple of levels at most; the bound only exists so a
// config that somehow points a group at itself cannot spin forever.
const maxGroupDepth = 8

// InUseNode names the leaf proxy the connection is on behind group, or ""
// when there is no single node to name.
//
// A group whose members are other groups routes through the member it has
// selected, so the node in use is found by following that selection down until
// a leaf is reached. It is what the watcher has to name: the group's own Now
// answers with the sub group it routes through, which is not a node the gate
// could measure or move the selection to.
//
// The empty answer matters as much as the name. A load balance picks per
// connection and reports no selection at all, so no single node runs behind it
// to watch, and a selection left behind for a removed proxy names nothing
// either. Reporting the group's own name in those cases would hand the watcher
// a "node" that is really the group, which is never a member of itself.
func InUseNode(group string) string {
	current := group

	for i := 0; i < maxGroupDepth; i++ {
		p := tunnel.Proxies()[current]
		if p == nil {
			// The name is not in the map: a selection left behind for a proxy
			// that is gone, so nothing runs through it.
			return ""
		}

		g, ok := p.Adapter().(outboundgroup.ProxyGroup)
		if !ok {
			// A leaf: the name the connection runs through.
			return current
		}

		next := g.Now()
		if next == "" || next == current {
			// A group that does not name one of its members, which is how a
			// load balance reports itself, or a group pointing at itself:
			// neither has a node to name.
			return ""
		}

		current = next
	}

	// Deeper than real nesting, or a cycle the depth bound gave up on.
	return ""
}

func convertProxies(proxies []C.Proxy, uiSubtitlePattern *regexp2.Regexp) []*Proxy {
	result := make([]*Proxy, 0, 128)

	for _, p := range proxies {
		name := p.Name()
		title := name
		subtitle := p.Type().String()

		if uiSubtitlePattern != nil {
			if _, ok := p.Adapter().(outboundgroup.ProxyGroup); !ok {
				runes := []rune(name)
				match, err := uiSubtitlePattern.FindRunesMatch(runes)
				if err == nil && match != nil {
					title = string(runes[:match.Index]) + string(runes[match.Index+match.Length:])
					subtitle = string(runes[match.Index : match.Index+match.Length])
				}
			}
		}
		testURL := "https://www.gstatic.com/generate_204"
		for k := range p.ExtraDelayHistories() {
			if len(k) > 0 {
				testURL = k
				break
			}
		}
		_, isGroup := p.Adapter().(outboundgroup.ProxyGroup)

		result = append(result, &Proxy{
			Name:     name,
			Title:    strings.TrimSpace(title),
			Subtitle: strings.TrimSpace(subtitle),
			Type:     p.Type().String(),
			Delay:    int(p.LastDelayForTestUrl(testURL)),
			IsGroup:  isGroup,
		})
	}
	return result
}

func collectProviders(providers []provider.ProxyProvider, uiSubtitlePattern *regexp2.Regexp) []*Proxy {
	result := make([]*Proxy, 0, 128)

	for _, p := range providers {
		for _, px := range p.Proxies() {
			name := px.Name()
			title := name
			subtitle := px.Type().String()

			if uiSubtitlePattern != nil {
				if _, ok := px.Adapter().(outboundgroup.ProxyGroup); !ok {
					runes := []rune(name)
					match, err := uiSubtitlePattern.FindRunesMatch(runes)
					if err == nil && match != nil {
						title = string(runes[:match.Index]) + string(runes[match.Index+match.Length:])
						subtitle = string(runes[match.Index : match.Index+match.Length])
					}
				}
			}

			testURL := "https://www.gstatic.com/generate_204"
			for k := range px.ExtraDelayHistories() {
				if len(k) > 0 {
					testURL = k
					break
				}
			}
			_, isGroup := px.Adapter().(outboundgroup.ProxyGroup)

			result = append(result, &Proxy{
				Name:     name,
				Title:    strings.TrimSpace(title),
				Subtitle: strings.TrimSpace(subtitle),
				Type:     px.Type().String(),
				Delay:    int(px.LastDelayForTestUrl(testURL)),
				IsGroup:  isGroup,
			})
		}
	}

	return result
}
