package probtest

import (
	"fmt"
	"regexp"
	"strings"
	"unicode"

	"gopkg.in/yaml.v3"
)

// decodeRoot decodes a subscription document into a generic mapping so the
// original structure can be rewritten verbatim later.
func decodeRoot(raw []byte) (map[string]any, error) {
	if len(raw) == 0 {
		return nil, fmt.Errorf("empty document")
	}

	var root map[string]any

	if err := yaml.Unmarshal(raw, &root); err != nil {
		return nil, fmt.Errorf("decode yaml: %w", err)
	}

	if root == nil {
		return nil, fmt.Errorf("document is not a mapping")
	}

	return root, nil
}

// collectEntries returns the raw proxy entries of a subscription, preserving
// the document order. Entries that are not mappings are kept as-is so they
// can be reported as rejected instead of silently disappearing.
func collectEntries(root map[string]any) ([]any, error) {
	raw, ok := root["proxies"]
	if !ok || raw == nil {
		if providers, has := root["proxy-providers"]; has && providers != nil {
			return nil, fmt.Errorf("subscription only declares proxy-providers; inline `proxies` required")
		}
		return nil, fmt.Errorf("document has no `proxies` list")
	}

	switch v := raw.(type) {
	case []any:
		if len(v) == 0 {
			return nil, fmt.Errorf("`proxies` list is empty")
		}
		return v, nil
	case []map[string]any:
		if len(v) == 0 {
			return nil, fmt.Errorf("`proxies` list is empty")
		}
		out := make([]any, 0, len(v))
		for _, e := range v {
			out = append(out, e)
		}
		return out, nil
	default:
		return nil, fmt.Errorf("`proxies` is %T, expected a list", raw)
	}
}

func nodeName(entry any, idx int) string {
	if m, ok := entry.(map[string]any); ok {
		if name, ok := m["name"].(string); ok && name != "" {
			return name
		}
	}
	return fmt.Sprintf("#%d", idx)
}

func stringType(entry any) string {
	if m, ok := entry.(map[string]any); ok {
		if typ, ok := m["type"].(string); ok {
			return typ
		}
	}
	return ""
}

// informationPrefixes are the words an airport puts at the front of the
// placeholder nodes it appends to a subscription to carry its own account
// information: "剩余流量：712.92 GB", "套餐到期：2026-11-24". They are ordinary
// proxies and they answer like one - some of them carry real traffic at usable
// speed - so no amount of probing tells them apart from a node. The name is
// the only thing that says what they are.
//
// Matched against the front of the name rather than anywhere in it, because a
// node legitimately called "香港 剩余流量优化" is a node. The cost of that
// choice is a provider that prefixes its information nodes with a location, and
// those stay in the pool; the cost of matching anywhere is losing real nodes
// over a word, which is worse because it is silent and permanent.
var informationPrefixes = []string{
	"剩余流量",
	"剩余",
	"流量剩余",
	"已用流量",
	"套餐流量",
	"总流量",
	"套餐到期",
	"到期时间",
	"到期日",
	"过期时间",
	"有效期",
	"距离下次重置",
	"距下次重置",
	"下次重置",
	"重置剩余",
	"距离重置",
}

// A name that is nothing but a link or a date, which is the same thing wearing
// no words at all.
var (
	bareURL  = regexp.MustCompile(`^(https?://|www\.)\S+$`)
	bareDate = regexp.MustCompile(`^\d{4}\s*[-/年.]\s*\d{1,2}\s*[-/月.]\s*\d{1,2}\s*日?$`)
)

// InformationNode reports why a node name is one of a subscription's
// information entries rather than the name of a node, or returns "" when it
// names one.
//
// It is deliberately a separate question from whether an entry can be parsed
// safely: a well-formed information node passes every check ParseProxy makes
// and is still not something to connect through. Two callers share it because
// both of them can end up holding one - a screening round that publishes
// them, and a live speed test that ranks them and may pick one as the node a
// group connects through.
//
// The leading flag emoji, symbols and spacing a provider puts in front of a
// name are stripped first, since they are what separate "🇭🇰 剩余流量：1 TB" from
// the word that identifies the entry. A name that was only decoration has
// nothing left afterwards and reads as absent rather than as information.
func InformationNode(name string) string {
	name = strings.TrimLeftFunc(strings.TrimSpace(name), func(r rune) bool {
		return !unicode.IsLetter(r) && !unicode.IsDigit(r)
	})
	if name == "" {
		return ""
	}

	for _, prefix := range informationPrefixes {
		if strings.HasPrefix(name, prefix) {
			return fmt.Sprintf("subscription information, not a node (%q)", prefix)
		}
	}

	if bareURL.MatchString(name) {
		return "subscription information, not a node (a bare link)"
	}
	if bareDate.MatchString(name) {
		return "subscription information, not a node (a bare date)"
	}

	return ""
}

// informationReason is InformationNode applied to an entry that may not be a
// mapping at all.
func informationReason(entry any) string {
	m, ok := entry.(map[string]any)
	if !ok {
		return ""
	}

	name, ok := m["name"].(string)
	if !ok {
		return ""
	}

	return InformationNode(name)
}

