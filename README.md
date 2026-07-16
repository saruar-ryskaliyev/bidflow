# bidflow

[![build](https://github.com/saruar-ryskaliyev/bidflow/actions/workflows/build.yml/badge.svg)](https://github.com/saruar-ryskaliyev/bidflow/actions/workflows/build.yml)

A real-time ad auction service in Java, built for the latency budget a sponsored-results
page actually has: the auction must resolve in microseconds, must never overcharge an
advertiser, and must produce a result that can be replayed bit-for-bit from a billing log
months later.

The interesting engineering is not the auction rule itself — generalized second-price
pricing is a handful of arithmetic. It is everything the rule has to survive: an
allocation-free hot path so the collector never runs during a request, exact integer money
so replay is deterministic, and a budget that is global while serving is sharded across
machines that cannot afford to talk to each other on the request path.

That last constraint is the real problem, and it is the one this project is organised
around. See [the hard problem](#the-hard-problem-distributed-budget-enforcement).

## Status

| Component | State |
| --- | --- |
| `auction-core` — GSP ranking and pricing | Implemented, 30 tests green |
| `sim` — deterministic simulation harness | Implemented, 31 tests green |
| `budget` — distributed budget enforcement | Safe baseline implemented, 31 tests green |
| Reclaiming stranded budget | Next |
| Pacing controller | Not started |
| Idempotent spend ledger | Not started |
| gRPC serving layer | Not started |
| JMH benchmarks and load harness | Not started |

Performance claims are deliberately absent from this README until the JMH harness exists.
The design is built for a sub-microsecond auction and zero steady-state allocation, but
neither is a measurement yet.

## The hard problem: distributed budget enforcement

A campaign's daily budget is a single global number. Serving is spread across many shards,
each of which must decide in microseconds whether an ad can still afford to show. Those two
facts are in direct conflict: consulting a central counter per request means a network round
trip inside a budget that does not have room for one.

The way out is for shards to hold *spend authority* locally — a lease over some slice of the
budget, granted in advance by an authority that owns the true remaining amount, and valid
only until it expires. The serving path then never blocks on the network; it decrements a
local counter.

That design turns one hard problem into two interesting ones.

**Safety is about clocks, not arithmetic.** A lease expires at an instant, and the granting
authority reclaims the unspent remainder at what it believes is that same instant. If the
holder's clock runs slow, there is a window in which both parties consider the same budget
theirs, and whatever can be spent in that window is overdelivery. The bound is therefore a
function of clock skew and peak spend rate, not of the accounting logic — which is why
`NodeClock` exists to give every simulated node a deliberately wrong clock, and why Spanner
had to build TrueTime rather than assume synchronised clocks.

**Efficiency pulls the other way.** Budget sitting in the lease of a shard receiving no
traffic is budget no one can spend, so an advertiser underdelivers while demand goes unmet
elsewhere. Shorter leases strand less but coordinate more. Larger leases coordinate less but
strand more and widen the skew window. There is no setting that is simply correct, which is
what makes it worth building and measuring rather than reasoning about.

The two properties to be demonstrated, under partition, crash, and skew:

- **Safety** — total spend never exceeds the budget by more than a stated, argued bound.
- **Efficiency** — when demand exceeds budget, delivery reaches within a stated fraction of
  it rather than stranding the remainder.

## Deterministic simulation

The `sim` module exists because those two properties cannot be tested any other way. The
failures that matter live at specific interleavings — a lease expiring while its reclaim is
still in flight, a shard dying between spending and reporting, two nodes disagreeing about
whether a deadline has passed. Against real threads and real clocks, hitting those is a
matter of luck, and luck does not survive a bug report.

So the harness owns everything nondeterministic: time is virtual and advances only when the
event queue does, the network delays and drops and duplicates and partitions on seeded
draws, node crashes discard pending timers the way a dying process does, and every clock is
a per-node fiction with configurable error. A whole simulated day costs whatever the work
costs, not a day. Most importantly, a failing run is described entirely by its seed — a
counterexample is a single number, not a story about timing.

This is the approach FoundationDB and TigerBeetle take, and it is the piece that has to be
built first: determinism cannot be retrofitted onto code that has already been written
against `System.nanoTime()` and real threads.

## Results so far

A sweep of 200 randomised deployments — varying shard count, top-up size, network quality, and
per-node clock skew, then injecting crashes and partitions on a schedule drawn from the seed:

| Measure | Result |
| --- | --- |
| Worst overspend across 200 seeds | **0 micros** |
| Shard restarts exercised | 945 |
| Messages discarded by partitions | 141,244 |
| Messages dropped by the network | 29,452 |
| Messages duplicated by the network | 12,813 |
| Budget delivered, mean | 67.1% |
| Budget delivered, worst seed | 6.0% |

Two findings, and the second is the interesting one.

**Safety holds unconditionally.** Not "held under the faults we tried" — the argument does not
mention clocks, ordering, retries, or crashes, so there is no fault of that kind for it to fail
under. The sweep is evidence for the implementation matching the argument, not for the argument
itself.

**Efficiency is poor, and now it is poor with a number attached.** Delivering 67% of budget on
average is not good enough: an advertiser who asked to spend 100 and spent 67 is a lost
customer. The worst seed delivered 6%, which is a shard cut off from the authority early and
holding money nobody else could reach. Every micro of that gap is authority granted to a
process that crashed or got partitioned away, and the current design never takes any of it
back.

That gap is the whole subject of the next phase. Closing it means letting the authority reclaim
unspent grants, which means revocation, which means a revoke can race a shard that is still
spending — and at that point overspend stops being structurally impossible and becomes a
quantity bounded by clock skew. The honest way to make that trade is to have measured the safe
baseline first, which is what these numbers are for.

Two known inefficiencies already visible and not yet addressed: a shard whose reply is slow
will ask again on its cooldown and can accumulate several grants it did not need, and a shard
holding less than the cheapest request cannot spend its remainder.

## The mechanism

Candidates are ranked by **ad rank**, the product of the bid and the predicted quality:

```
adRank = bidMicros * qualityBps
```

Ranking on the product rather than the bid alone is what stops the top slot from going to
whoever has the deepest pockets. A mediocre ad has to outbid a good one by the ratio of
their quality scores.

Each winner then pays the least it could have bid and still held the slot it won — the ad
rank of the candidate directly below it, converted back into a price at the winner's own
quality:

```
price_k = ceil(adRank_(k+1) / quality_k)
```

Dividing by the winner's own quality is the detail that matters. Two ads defending the same
slot against the same rival pay different amounts, and the more relevant one pays less.

### Worked example

Three slots, reserve 5,000 micros, all candidates at quality 1.0:

| Campaign | Bid (micros) | Quality | Ad rank | Slot | Pays |
| --- | --- | --- | --- | --- | --- |
| A | 100,000 | 1.00 | 1,000,000,000 | 1 | 80,000 |
| B | 80,000 | 1.00 | 800,000,000 | 2 | 60,000 |
| C | 60,000 | 1.00 | 600,000,000 | 3 | 40,000 |
| D | 40,000 | 1.00 | 400,000,000 | — | — |

Nobody pays their own bid; each pays just enough to hold off the ad beneath it, and D sets
the price of the last slot without winning anything.

Now drop A's quality to 0.50. Its ad rank falls to 500,000,000 and it loses the top slot to
B and C outright — a 25% bid premium over B could not buy back a 50% relevance deficit.

## Design decisions

**Money is integral, never floating point.** Every amount is a `long` count of micros
(millionths of a currency unit). Binary floating point cannot represent decimal currency
exactly, and an auction re-run against a billing log has to produce an identical answer —
not one within a rounding error. Quality is likewise an integer in basis points, so ad rank
is exact integer arithmetic with no rounding drift and no platform-dependent results.

**Bids are range-checked on the way in so the hot path never checks for overflow.**
`MAX_BID_MICROS * QUALITY_ONE_BPS` is 10^13, comfortably inside a `long`, which means the
ranking loop can multiply freely. The validation cost is paid once per candidate at
admission rather than repeatedly during ranking.

**Candidates are a struct-of-arrays, not an array of objects.** Three parallel primitive
arrays keep the fields the ranking loop reads contiguous in memory and remove a pointer
dereference per comparison. An array of `Candidate` objects would scatter the hot fields
across the heap and add a header to each.

**Buffers are allocated once and reused.** `AuctionRequest`, `AuctionOutcome`, and the
engine's scratch space are all sized in their constructors and reset between auctions, so a
warmed serving thread allocates nothing per request. This is the difference between a p99
that reflects the auction and a p99 that reflects a young-generation collection.

**Selection is a partial sort, not a full one.** Only the first `slots + 1` candidates need
ordering — one beyond the last winner, because the bottom winner is priced against the best
loser. A partial selection sort costs `O(n * slots)`, which for the handful of slots a real
page offers is both fewer comparisons than `O(n log n)` and free of the comparator
allocation a `Comparator`-based sort would need.

**The reserve is a floor on rank, not on price.** Converting the reserve price into a
reserve on ad rank means a high-quality ad clears the same bar with a lower bid, and its
resulting floor price is proportionally lower. Relevance is cheaper than brute force at the
reserve too, not just in the ranking.

**Ties break on campaign id.** Any total order would do, but it has to be deterministic.
An auction replayed for billing or debugging must reproduce the original result, which rules
out leaving equal-rank candidates in arbitrary input order.

**One slot per campaign.** Losing creatives of a campaign that has already won are retired
by swapping them past the live range, keeping survivors contiguous at `O(slots)` per pick —
the same cost class as the selection itself.

**The engine is thread-confined rather than synchronized.** It carries mutable scratch
state, so each request-handling thread gets its own instance. Contended shared state is the
wrong trade at this latency target.

## Testing

Two layers, deliberately different in kind.

`AuctionEngineTest` holds hand-picked worked examples. The numbers are chosen so that a
regression names the specific rule it broke — that quality discounts price, that rounding
goes up, that the sole bidder falls back to the reserve.

`AuctionPropertiesTest` states the invariants and lets jqwik attack them with 2,000
generated candidate sets each: no advertiser is charged above its bid, no winner pays below
its quality-adjusted floor, ranks descend down the page, a campaign never takes two slots,
every winner cleared the reserve, no slot sits empty while an eligible campaign waits, the
same input always yields the same outcome, and bidding more never costs a campaign its
position. Property tests suit an auction unusually well: the guarantees are far easier to
state than to enumerate, and the generator reliably produces the cases a human would not
think to write — everybody tied at the same rank, one campaign holding forty creatives, a
reserve that excludes all but one bidder. On failure jqwik shrinks to a minimal
counterexample, which usually names the bug outright.

`DeterminismTest` checks the harness itself, since every later claim about budget safety will
take the form "seed 8,472 overspent by 0.03%" and such a claim is worthless if the seed does
not reproduce. It runs a deliberately messy scenario — five nodes gossiping over a hostile
network, a partition opening and healing, a node crashing and restarting — and asserts the
trace digests match across runs and across 25 different seeds. It also asserts that
disabling tracing changes nothing, because observation that perturbs the system would mean a
long untraced run could not reproduce what a short traced run found.

Both suites have been checked against deliberately introduced bugs rather than assumed
effective, because a green suite whose tests cannot fail is worse than no suite — it gets
trusted.

- Disabling the one-slot-per-campaign rule was caught by three tests, with the property
  shrinking to the minimal counterexample `campaign 1 repeated at position 1`.
- Adding a single `System.nanoTime()` call that perturbed simulated network latency by 0–2
  nanoseconds was caught by both determinism tests.
- Removing the insertion-order tiebreak among simultaneous events was caught only by the
  FIFO-ordering test, **not** by the determinism tests. That result corrected a wrong belief
  rather than confirming a right one: a binary heap resolves equal keys reproducibly, so the
  tiebreak is not what buys determinism. It is what stops event order from being arbitrary
  and from shifting when unrelated events are added elsewhere in a scenario. The comment in
  `Simulation` now says so.

Compilation runs under `-Xlint:all -Werror`.

## Build

Requires JDK 25. The Gradle wrapper handles the rest.

```bash
./gradlew build          # compile and test everything
./gradlew :auction-core:test
```

## Roadmap

Done: the budget authority, the shard-local wallet, and the fault-injection sweep establishing
that the safe baseline never overspends.

1. **Reclaim stranded budget** — lease expiry with a safety margin sized to bounded clock skew,
   so the authority can take back unspent grants. This is where overspend stops being
   structurally impossible and becomes a bounded quantity, and where the 67% delivery figure
   has to improve without the zero-overspend figure moving.
2. **Quantify the trade** — sweep lease duration and skew tolerance, and produce the curve
   relating recovered delivery to worst-case overspend. The curve is the actual deliverable;
   any single configuration is just a point on it.
3. **Suppress the request stampede** — a shard should not accumulate grants it did not need
   because its first reply was slow.
4. **Pacing controller** — spend the budget smoothly across a day of varying traffic rather
   than exhausting it by mid-morning, built on the lease mechanism rather than beside it.
5. **Wire in the auction** — replace the harness's synthetic per-request cost with a real
   `auction-core` auction, so spend is driven by prices the auction actually cleared.
6. **Spend ledger** — write-ahead log with periodic snapshots and idempotency keys, so a
   retried click is charged exactly once.
7. **JMH harness** — per-auction and per-`tryReserve` cost, and zero steady-state allocation
   proven with `GcProfiler`.
8. **gRPC serving** — deadline propagation and load shedding, because a late ad response is
   worth nothing and shedding beats queueing.
9. **Load harness** — HDR histogram percentiles recorded free of coordinated omission, plus
   a ZGC-versus-G1 comparison under sustained load.
