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
// so a reader can line the two numbers up, and the adapter the measurement
// runs through.
type SpeedTarget struct {
	Name  string
	Delay int
	Proxy C.Proxy
}

// GroupSpeedTargets resolves a group to the nodes a speed test may measure.
//
// Only leaf proxies are returned. A nested group is skipped because measuring
// it would measure the nodes behind it a second time through an extra hop,
// and the built-in DIRECT/REJECT entries are skipped because they carry no
// exit: timing them would measure the device's own link instead of a node a
// reader could pick. A missing or non-group name is an error so a toggle left
// behind for a removed group fails loudly instead of silently measuring
// nothing.
func GroupSpeedTargets(name string) ([]SpeedTarget, error) {
	p := tunnel.Proxies()[name]
	if p == nil {
		return nil, fmt.Errorf("group `%s` not found", name)
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		return nil, fmt.Errorf("`%s` is not a group (%s)", name, p.Type().String())
	}

	proxies := g.Proxies()
	result := make([]SpeedTarget, 0, len(proxies))

	for _, px := range proxies {
		if _, isGroup := px.Adapter().(outboundgroup.ProxyGroup); isGroup {
			continue
		}

		switch px.Name() {
		case "DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE", "GLOBAL":
			continue
		}

		testURL := "https://www.gstatic.com/generate_204"
		for k := range px.ExtraDelayHistories() {
			if len(k) > 0 {
				testURL = k
				break
			}
		}

		result = append(result, SpeedTarget{
			Name:  px.Name(),
			Delay: int(px.LastDelayForTestUrl(testURL)),
			Proxy: px,
		})
	}

	return result, nil
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
