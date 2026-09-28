package probtest

import (
	"fmt"

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
