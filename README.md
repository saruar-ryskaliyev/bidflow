# bidflow

[build](https://github.com/saruar-ryskaliyev/bidflow/actions/workflows/build.yml)

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


| Component                                          | State                                                              |
| -------------------------------------------------- | ------------------------------------------------------------------ |
| `auction-core` — GSP ranking and pricing           | Implemented, 30 tests green                                        |
| `sim` — deterministic simulation harness           | Implemented, 38 tests green                                        |
| `budget` — leases, reclaim, and pacing             | Implemented, 59 tests green                                        |
| `budget-sim` — multi-shard fault scenarios         | Implemented, 16 tests green                                        |
| `sim-explorer` — interactive shard explorer        | Implemented, 20 tests green, `./gradlew :sim-explorer:run`         |
| `ledger` — checksummed WAL + idempotent charges    | Implemented, 8 tests green                                         |
| `demo` — browser demo of auction + budget          | Implemented, 6 tests green, `./gradlew :demo:run`                  |
| `serving` — budget-aware gRPC + shedding           | Implemented, 15 tests green, `./gradlew :serving:run`              |
| `load` — open-loop HDR harness + G1/ZGC scripts    | Implemented, 3 tests green                                         |
| `bench` — JMH microbenchmarks                      | Implemented                                                        |


Performance is no longer a claim: the auction and the serving-path budget check are
measured under JMH in [Measured performance](#measured-performance), and sustained
serving latency under open-loop load is in [Load and GC](#load-and-gc).

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

## Results

Both experiments drive spend through the real auction: every simulated request pits the
budget-enforced campaign against seeded competitors, and what the wallet reserves is the
GSP price the engine actually cleared. Losing the auction spends nothing, exactly as in
production.

### The safety theorem

**Overspend can never exceed what the authority reclaimed unilaterally.**

Every micro of overspend comes from settling a lease below what was actually spent on it and
re-leasing the difference, and that difference is exactly the reclaimed portion of that lease.
Summing over all leases bounds total overspend by total reclaim. Two consequences define the ends
of the trade-off: reclaim nothing and overspend is exactly zero, unconditionally; reclaim
aggressively and the exposure is whatever was reclaimed.

The bound is asserted on every run of both experiments below, and across 120 randomised
fault-injection seeds. It has never been violated.

### Experiment 1 — the clock-skew boundary

Every shard's clock runs 150 ms behind the authority — healthy network, frequent reports, no
faults. Twelve seeds per row.


| Reclaim margin | Spent   | Overspend | Reclaimed |
| -------------- | ------- | --------- | --------- |
| 0 ms           | 135.94% | 35.94%    | 137.36%   |
| 50 ms          | 135.94% | 35.94%    | 108.65%   |
| 100 ms         | 118.64% | 18.64%    | 69.97%    |
| 150 ms         | 100.19% | 0.19%     | 3.55%     |
| 300 ms         | 100.00% | 0.00%     | 2.74%     |


An impatient authority takes leases back from shards that are still spending on them, and the
overspend is severe — 36% of budget, and a reclaim total larger than the budget itself because
the same money churns repeatedly. Once the margin covers the skew, the clock overspend is gone.

What remains at covered margins — a fifth of a percent at 150 ms, a thousandth at 300 ms — is a
different animal, exposed by prefetched renewal. At the budget's exhaustion tail the authority
has nothing left to grant, so a wallet that would once have sealed keeps spending its live
lease; the sweeper eventually settles that lease at its last report, and whatever was spent
after that report is freed and re-leased. The residue is bounded by one report interval of
spending per shard — report lag, not clock skew — and it stays inside the
overspend-is-bounded-by-reclaim theorem like everything else. The crossing between the regimes
still sits below the skew, because a shard asks for its next lease ahead of expiry and stops
spending the old one the moment the grant lands.

### Experiment 2 — the trade-off under crashes and partitions

Randomised deployments with crashes, partitions, and ±50 ms skew, drawn from the seed. This is
where reclaim earns its keep, because crashed and partitioned shards never release anything.
Twelve seeds per row.


| Configuration                   | Delivered | Overspend | Reclaimed |
| ------------------------------- | --------- | --------- | --------- |
| No expiry (the previous design) | 73.92%    | **0.00%** | 0.00%     |
| Expiry, never reclaim           | 57.97%    | **0.00%** | 0.00%     |
| Expiry + reclaim, 500 ms margin | 61.17%    | 0.000%    | 25.74%    |
| Expiry + reclaim, 200 ms margin | 62.63%    | 0.056%    | 31.04%    |
| Expiry + reclaim, 100 ms margin | 63.18%    | 0.366%    | 40.18%    |
| Expiry + reclaim, 50 ms margin  | 63.24%    | 0.423%    | 83.64%    |
| Expiry + reclaim, 0 ms margin   | 64.42%    | 1.606%    | 146.09%   |


Three findings.

**Expiry's in-window cost is partition exposure, not protocol overhead.** With the request
stampede and the renewal gap both fixed (below), a never-expiring lease serves straight through
a partition — the wallet needs no contact — while an expiring lease dies within its duration and
the shard goes dark until the partition heals. That is most of the sixteen-point gap between
73.92% and 57.97%.

**Reclaim moves money, not serving time.** Recovering an unreachable shard's lease and
re-leasing it to shards that can still be reached claws back six and a half of those points
(57.97% to 64.42%), and a patient 500 ms margin takes 3.2 of them with no measured overspend at
all. The rest cannot be bought this way: the dark shard's requests are not being served by
anyone, and money cannot fix that.

**The remaining gap is the price of revocability.** Within three seconds, never expiring wins on
delivery — but its loss per crash is permanent, invisible in a window this short and unbounded
over a day, while expiry caps every crash at one lease of face value plus a margin's wait. Which
side of that trade to take is the business question the curve prices. The properties asserted
are the ones that hold at any horizon: reclaim beats expiry alone, the configurations that never
reclaim never overspend, and overspend never exceeds reclaim.

The comparison against "no expiry" is measured under the identical fault mix rather than quoted
from an earlier run. An improvement demonstrated against a differently-configured baseline would
not be an improvement.

### Known inefficiencies, two fixed and measured

**Fixed: the renewal request stampede.** A shard whose reply was slow used to ask again on its
cooldown, and every retry minted another lease that stranded until the sweeper collected it. The
authority now answers a retry by retransmitting the still-live previous grant — safe because the
wallet already treats a duplicated grant as a no-op. On its own this moved the no-expiry baseline
from 53.57% to 71.97% delivered and cut zero-margin reclaim churn from 433% of budget to 132%
with overspend unchanged: most of that churn had been the sweeper recycling stampede strandings.

**Fixed: the sealed renewal gap.** Renewal was seal-then-ask — the wallet stopped spending, asked,
and served nothing for a round trip. It now prefetches: the wallet keeps spending its live lease
while the next one is in flight, and installing the grant displaces the old lease into a pending
release whose final figure travels by three idempotent carriers — an immediate release message,
the next lease request, and the periodic reports. Worth roughly two to six points of delivery
across the expiring configurations, on top of the stampede fix.

The simulator caught two protocol bugs in the prefetch before any of it was trusted, both as
seed-reproducible measurements rather than review comments. First, a request must tell the
authority what it *holds* separately from what it *settles*: conflating the two made grant
retransmission answer a prefetch with the lease the shard already had, starving it of the next —
under never-expiring leases the starvation is permanent, and delivery collapsed to 19% until the
ids were separated. Second, prefetch keeps exhausted-tail wallets spending where seal-then-ask
had silenced them, which trades the old exactly-zero overspend at covered margins for a residue
bounded by report lag — 0.19% at a margin equal to the skew, and the reason experiment 1's
assertion now reads "bounded" rather than "zero".

One inefficiency remains: a shard holding less than the price of a click cannot spend its
remainder, though the prompt release now recycles displaced remainders quickly.

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


| Campaign | Bid (micros) | Quality | Ad rank       | Slot | Pays   |
| -------- | ------------ | ------- | ------------- | ---- | ------ |
| A        | 100,000      | 1.00    | 1,000,000,000 | 1    | 80,000 |
| B        | 80,000       | 1.00    | 800,000,000   | 2    | 60,000 |
| C        | 60,000       | 1.00    | 600,000,000   | 3    | 40,000 |
| D        | 40,000       | 1.00    | 400,000,000   | —    | —      |


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

## Measured performance

JMH 1.37 on an Apple M4 (macOS 15.6), OpenJDK 25, average time over two forks with the GC
profiler attached. `./gradlew :bench:run --args="-prof gc"` reproduces the run.


| Operation                                   | Cost            | Allocation |
| ------------------------------------------- | --------------- | ---------- |
| Full auction — 64 candidates, 3 slots       | 274.7 ± 12.2 ns | ≈ 0 B/op   |
| Full auction — 8 candidates, 3 slots        | 40.2 ± 1.6 ns   | ≈ 0 B/op   |
| `tryReserve` — the per-request budget check | 1.91 ± 0.15 ns  | ≈ 0 B/op   |


The auction fits its microsecond budget more than three times over at the largest
candidate set benchmarked, and the budget check is cheap enough that enforcement adds
nothing measurable to a request. The allocation column is the earlier design claim made
good: `gc.alloc.rate.norm` reports under a hundredth of a byte per operation — residue of
JMH's own infrastructure — and no collection ran during measurement, so a warmed serving
thread gives the collector no work to do.

The honest caveats: these are single-threaded microbenchmark averages on one quiet
machine, not a loaded service's p99. Sustained tail latency under open-loop load is
measured separately in [Load and GC](#load-and-gc).

## End-to-end data flow

The portfolio-complete path is:

```
paced BudgetAuthority ──async lease──▶ ServingShard wallet
                                            │
Client ──RunAuction──▶ GSP (local omit if bid > wallet)
                                            │
                                   opaque auction token
                                            │
Client ──RecordClick(token, slot, key)──▶ receipt store
                                            │
                                   SpendLedger.charge (WAL + force)
                                            │
                                   wallet reserve / refuse / replay
```

Global remaining budget lives in a single-owner `BudgetAuthority`. A deterministic
`PacingController` caps lease grants against a target spend curve so a day of budget is
not exhausted by mid-morning. Shards hold local `SpendAuthority` wallets from those
leases and never consult the authority on the auction path. When a click arrives, the
server — not the client — supplies campaign and cleared GSP price from a bounded receipt
store; the `SpendLedger` persists the outcome under an idempotency key before
acknowledging, so a retried click cannot double-bill.

## The serving layer

The auction is only useful if it answers before the page has moved on. The `serving`
module puts the engine behind gRPC with the controls that matter at that deadline:

**Shedding happens at admission, not in a queue.** An interceptor holds an inflight
semaphore sized to the worker count plus a configured queue depth. The permit is held
until the call closes or cancels — not merely until the request stream completes — so
`workers + queueDepth` is the true maximum outstanding work. Excess calls are closed
with `RESOURCE_EXHAUSTED` on the transport thread.

**Workers are fixed platform threads, not virtual threads.** Each worker owns a
`ThreadLocal` (or shard-owned) bundle of auction buffers, so a warmed thread allocates
nothing for the auction itself. The gRPC transport keeps its own executor for call
lifecycle; the handler hops onto the worker pool so a parked auction cannot stall
admission. Client deadlines propagate through `Context`.

**Budget-aware mode wires leases and clicks.** `AuctionServer … budget [ledgerDir]`
starts paced campaign authorities, per-shard wallets, a receipt store, and a durable
ledger. `RunAuction` returns an opaque auction token; `RecordClick` charges the
server-owned GSP price exactly once under the client's idempotency key. Lease
prefetch/report/release stays asynchronous — a blocked coordinator does not stall
auctions that still hold a live lease.

Pure-auction mode (`./gradlew :serving:run`) remains available for transport and
shedding experiments without a ledger.

## Pacing

`LeaseGrantPolicy` / `PacingController` sit in front of lease issuance. The default
`UnpacedGrantPolicy` preserves the original grant behaviour for simulation baselines.
The proportional controller compares observed spend (`settled + outstanding reported`)
to a linear target curve in fixed-point basis points and returns a smoothed grant cap
in `[0, requestedMicros]`. Front-loaded and varying demand profiles in `BudgetCluster`
plus unit/property tests keep delivery near the curve without violating the
overspend-is-bounded-by-reclaim theorem.

## Spend ledger

The `:ledger` module is a single-writer, database-free durability layer:

- length-prefixed, versioned WAL records with CRC32C;
- torn final records truncated on recovery; corruption before the tail fails closed;
- checksummed snapshots written to a temp file, forced, then atomically renamed;
- `charge(idempotencyKey, …)` returns `ACCEPTED`, `REPLAYED`, `REFUSED`, or `CONFLICT`.

If persistence fails after a wallet reservation, the RPC fails and authority is stranded
rather than risking a double bill; recovery reconciles per-lease committed totals before
a restarted shard requests fresh leases.

## Load and GC

The `:load` module is a separate open-loop client. Arrivals are scheduled at a fixed
intended RPS; completion latency is recorded from the *intended* start, so stalls appear
as long samples instead of being omitted (coordinated-omission-free). Results include
HdrHistogram percentiles, throughput, deadline, and saturation counts as JSON under
`load/build/results`.

**Measured smoke (Apple M4, macOS 15.6, OpenJDK 25, pure-auction server, 64 candidates,
1500 RPS, 2 s warmup + 8 s measure, default 50 ms deadline):**


| Metric | Value      |
| ------ | ---------- |
| count  | 12,000     |
| mean   | 0.707 ms   |
| p50    | 0.293 ms   |
| p90    | 0.444 ms   |
| p99    | 16.8 ms    |
| p99.9  | 47.1 ms    |
| max    | 55.7 ms    |
| shed   | 0 / 0      |


Reproduce a short run:

```bash
./gradlew :serving:installDist :load:installDist
serving/build/install/serving/bin/serving 50051 &
load/build/install/load/bin/load \
  --host localhost --port 50051 --rps 1500 --warmup 2 --duration 8 \
  --candidates 64 --out load/build/results/portfolio-smoke.json
```

**60-second G1 vs ZGC** (same machine, 2000 intended RPS, 5 s warmup + 60 s measure,
64 candidates, 200 ms client deadline, identical client flags):


| Collector | Throughput | p50     | p90    | p99     | p99.9   | Deadline | Saturated |
| --------- | ---------- | ------- | ------ | ------- | ------- | -------- | --------- |
| G1        | 1869.8 rps | 0.323 ms | 13.9 ms | 1400 ms | 2055 ms | 6533     | 1281      |
| ZGC       | 1984.7 rps | 0.299 ms | 2.02 ms | 77.5 ms | 192 ms  | 0        | 878       |


This run does **not** assert a winner. On this laptop under this open-loop offer rate,
ZGC kept intended-start tails and deadline misses far lower; G1 spent more of the
window recovering. Re-run on your hardware:

```bash
./load/scripts/run-gc-compare.sh
# writes load/build/results/{g1,zgc}.json and matching *.gc.log
```

Caveats: one quiet laptop; CI only runs the load module's unit/smoke tests; the
workspace path may contain spaces, so the compare script parks GC logs under
`/tmp/bidflow-gc-compare` before copying them into `load/build/results`. Treat the
60-second script as the acceptance measurement.

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
./gradlew :demo:run           # one-process auction sandbox at http://localhost:8080
./gradlew :sim-explorer:run   # deterministic shard explorer at http://127.0.0.1:8081
./gradlew :serving:run        # pure-auction gRPC on :50051
# budget-aware (leases + RecordClick + ledger):
./gradlew :serving:installDist
serving/build/install/serving/bin/serving 50051 budget build/ledger
./gradlew :load:run --args="--host localhost --port 50051 --rps 1000 --warmup 1 --duration 5 --out load/build/results/out.json"
```

The demo serves a page where two users with different quality scores search the same
market: the real engine prices each page, clicks charge real leases, and campaigns drop
out as budgets exhaust. It runs in one process on one clock, so the skew and partition
behaviour quantified above is deliberately absent.

The shard explorer is the interactive face of that distributed behaviour. It drives the
same seeded `Simulation` and `BudgetCluster` used by the safety experiments: choose a
shard count before the run, play/pause/step virtual time, inject searches, crash and
restart shards, partition and heal links, and watch authority↔shard messages plus
correctly labelled global and local balances. Interactive clicks are recorded as a
command journal, so Replay rebuilds an identical run from the seed and accepted
commands. The page binds to loopback only (`127.0.0.1:8081`) and is a local lab tool,
not a public server.

## Portfolio complete

Done for the selected finish line: GSP auction, deterministic simulator, lease-based
budget enforcement with unilateral reclaim and paced grants, checksummed idempotent
spend ledger, budget-aware gRPC serving (no central round trip on the auction path),
JMH microbenchmarks, an open-loop load/GC harness, and an interactive deterministic
shard explorer.

## Further research

These remain useful but are **not** blockers to project completion:

1. **Price revocability over longer horizons** — in a three-second window, never-expiring
   leases out-deliver expiry plus reclaim by nine points because a partitioned shard keeps
   serving from its wallet, while their loss per crash is permanent and accumulates without
   bound. A run long enough to show the crossover would turn the last qualitative claim in
   the results into a measured one.
2. **Static CI simulation export** — the interactive explorer covers live control of a
   seeded run. A complementary static site generated by CI from a recorded experiment
   would still be useful for sharing the trade-off curve without running the JVM.

