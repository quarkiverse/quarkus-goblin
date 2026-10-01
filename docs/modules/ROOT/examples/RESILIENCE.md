# Resilience promises

<!--
Copy this file to the root of your application as RESILIENCE.md and fill it in.
The Goblin agent playbook tells agents to read it before experimenting: it is the part Goblin cannot know.
Keep it short. One line per promise beats a paragraph nobody reads.
-->

## What "healthy" means

- Main endpoints and their latency target (e.g. `GET /api/orders`: p99 under 300 ms):
- Error budget (e.g. under 1% of 5xx over 5 minutes):
- Health checks that must stay UP during a degradation:

## Dependencies and what protects them

| Dependency | Called from (class.method) | Protection | Expected behaviour when it fails |
|---|---|---|---|
| e.g. payment API (REST Client) | `OrderService.pay` | `@Timeout(500)` + `@Fallback` | answers "payment pending" in under 1 s |
| e.g. database | `OrderRepository` | `@Retry(maxRetries = 2)` | retried, then 503 |

## Promises to verify

<!-- Each line is a hypothesis an agent can turn into one experiment. -->

- When ..., then ... within ...

## Out of bounds

- Layers or endpoints not to assault (e.g. the login flow, an endpoint shared with another team):
- Who to ask before arming chaos:
