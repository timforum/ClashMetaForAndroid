package probtest

import (
	"fmt"

	"gopkg.in/yaml.v3"
)

// Skip records why one candidate document contributed nothing to the merge.
type Skip struct {
	Index  int    `json:"index"`
	Reason string `json:"reason"`
}

// Merge folds every candidate subscription into the single document that Run
// will probe. It never fails because one candidate is unusable: bad candidates
// are reported through skipped so the caller can surface them.
//
// The shape of the result is:
//   - `proxies` is the concatenation of every candidate's inline nodes, with
//     colliding names renamed so no node is silently discarded;
//   - `proxy-groups`/`rules` come from the first candidate that has them, so
//     the published configuration routes like a real subscription instead of
//     the synthesised fallback;
//   - `proxy-providers` is dropped, because a published configuration may not
//     re-introduce nodes that were never tested.
func Merge(candidates [][]byte) (merged []byte, skipped []Skip, err error) {
	type source struct {
		root    map[string]any
		entries []any
	}

	var kept []source
	var template map[string]any

	for i, raw := range candidates {
		root, err := decodeRoot(raw)
		if err != nil {
			skipped = append(skipped, Skip{Index: i, Reason: err.Error()})
			continue
		}

		entries, err := collectEntries(root)
		if err != nil {
			skipped = append(skipped, Skip{Index: i, Reason: err.Error()})
			continue
		}
		if len(entries) == 0 {
			skipped = append(skipped, Skip{Index: i, Reason: "no inline proxies"})
			continue
		}

		kept = append(kept, source{root: root, entries: entries})

		if template == nil {
			template = root
		} else if hasAny(root, "proxy-groups", "rules") && !hasAny(template, "proxy-groups", "rules") {
			template = root
		}
	}

	if len(kept) == 0 {
		return nil, skipped, fmt.Errorf("none of the %d candidates contained a testable proxy", len(candidates))
	}
	if template == nil {
		return nil, skipped, fmt.Errorf("no candidate could be decoded")
	}

	// Owner of every emitted name, so a later candidate can tell its own
	// duplicate apart from a collision with a different subscription.
	owner := make(map[string]int)

	var proxies []any

	for i, src := range kept {
		for _, entry := range src.entries {
			name := nodeName(entry, len(proxies))

			if existing, used := owner[name]; !used || existing == i {
				owner[name] = i
				proxies = append(proxies, entry)
				continue
			}

			renamed := uniqueName(name, i, owner, i)
			owner[renamed] = i
			proxies = append(proxies, renameEntry(entry, name, renamed))
		}
	}

	out := make(map[string]any, len(template))
	for k, v := range template {
		out[k] = v
	}

	out["proxies"] = proxies
	// The template's providers would reintroduce untested nodes.
	delete(out, "proxy-providers")

	merged, err = yaml.Marshal(out)
	if err != nil {
		return nil, skipped, fmt.Errorf("re-encoding merged candidates: %w", err)
	}

	return merged, skipped, nil
}

// uniqueName finds a free variant of name for candidate i.
func uniqueName(name string, i int, owner map[string]int, self int) string {
	candidate := fmt.Sprintf("%s (%d)", name, i)

	for n := 2; ; n++ {
		if existing, used := owner[candidate]; !used || existing == self {
			return candidate
		}
		candidate = fmt.Sprintf("%s (%d-%d)", name, i, n)
	}
}

func renameEntry(entry any, from, to string) any {
	m, ok := entry.(map[string]any)
	if !ok {
		return entry
	}

	clone := make(map[string]any, len(m))
	for k, v := range m {
		clone[k] = v
	}
	clone["name"] = to

	return clone
}

func hasAny(root map[string]any, keys ...string) bool {
	for _, k := range keys {
		if v, ok := root[k]; ok && v != nil {
			if list, ok := v.([]any); ok && len(list) > 0 {
				return true
			}
		}
	}

	return false
}
