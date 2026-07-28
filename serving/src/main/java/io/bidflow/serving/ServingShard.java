package io.bidflow.serving;

import io.bidflow.auction.AuctionEngine;
import io.bidflow.auction.AuctionOutcome;
import io.bidflow.auction.AuctionRequest;
import io.bidflow.budget.Lease;
import io.bidflow.budget.SpendAuthority;
import io.bidflow.ledger.ChargeResult;
import io.bidflow.ledger.ChargeStatus;
import io.bidflow.ledger.SpendLedger;
import io.bidflow.serving.v1.Candidate;
import io.bidflow.serving.v1.ClickStatus;
import io.bidflow.serving.v1.RecordClickRequest;
import io.bidflow.serving.v1.RecordClickResponse;
import io.bidflow.serving.v1.RunAuctionRequest;
import io.bidflow.serving.v1.RunAuctionResponse;
import io.bidflow.serving.v1.Slot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One serving shard: thread-confined auction buffers, per-campaign wallets, and a durable
 * spend ledger. Lease renewal talks to the coordinator asynchronously from the caller's
 * perspective — {@link #runAuction} never blocks on the authority.
 */
final class ServingShard implements AutoCloseable {

    private static final long LOW_WATER_MICROS = 50_000L;
    private static final long RENEW_AHEAD_NANOS = 50_000_000L;

    private final int shardId;
    private final long incarnation;
    private final CampaignBudgetCoordinator coordinator;
    private final AuctionEngine engine;
    private final AuctionRequest request;
    private final AuctionOutcome outcome;
    private final AuctionReceiptStore receipts;
    private final SpendLedger ledger;
    private final Map<Long, SpendAuthority> wallets = new HashMap<>();
    private final AtomicLong tokenSequence = new AtomicLong();

    ServingShard(
            int shardId,
            long incarnation,
            int maxCandidates,
            int maxSlots,
            CampaignBudgetCoordinator coordinator,
            AuctionReceiptStore receipts,
            Path ledgerDir)
            throws IOException {
        this.shardId = shardId;
        this.incarnation = incarnation;
        this.coordinator = coordinator;
        this.engine = new AuctionEngine(maxCandidates);
        this.request = new AuctionRequest(maxCandidates);
        this.outcome = new AuctionOutcome(maxSlots);
        this.receipts = receipts;
        this.ledger = new SpendLedger(ledgerDir, 64);
        for (CampaignBudgetConfig config : coordinator.configs()) {
            wallets.put(config.campaignId(), new SpendAuthority(shardId, incarnation));
        }
    }

    int shardId() {
        return shardId;
    }

    long incarnation() {
        return incarnation;
    }

    SpendLedger ledger() {
        return ledger;
    }

    RunAuctionResponse runAuction(RunAuctionRequest proto, long nowNanos) {
        ensureLeases(nowNanos);
        request.reset(proto.getSlots(), proto.getReservePriceMicros());
        for (int i = 0; i < proto.getCandidatesCount(); i++) {
            final Candidate c = proto.getCandidates(i);
            if (canAfford(c.getCampaignId(), c.getBidMicros(), nowNanos)) {
                request.add(c.getCampaignId(), c.getBidMicros(), c.getQualityBps());
            }
        }
        engine.run(request, outcome);

        final AuctionReceiptStore.SlotReceipt[] slotReceipts =
                new AuctionReceiptStore.SlotReceipt[outcome.size()];
        final RunAuctionResponse.Builder builder = RunAuctionResponse.newBuilder();
        for (int k = 0; k < outcome.size(); k++) {
            slotReceipts[k] = new AuctionReceiptStore.SlotReceipt(
                    outcome.campaignId(k),
                    outcome.priceMicros(k),
                    outcome.adRank(k),
                    outcome.qualityBps(k));
            builder.addSlots(Slot.newBuilder()
                    .setCampaignId(outcome.campaignId(k))
                    .setPriceMicros(outcome.priceMicros(k))
                    .setAdRank(outcome.adRank(k))
                    .setQualityBps(outcome.qualityBps(k))
                    .build());
        }
        final String token = shardId + ":" + incarnation + ":" + tokenSequence.incrementAndGet();
        receipts.put(token, shardId, nowNanos, slotReceipts);
        return builder.setAuctionToken(token).build();
    }

    RecordClickResponse recordClick(RecordClickRequest proto, long nowNanos) throws IOException {
        final AuctionReceiptStore.Receipt receipt = receipts.get(proto.getAuctionToken(), nowNanos);
        if (receipt == null || receipt.shardId() != shardId) {
            return RecordClickResponse.newBuilder().setStatus(ClickStatus.CLICK_INVALID).build();
        }
        if (proto.getSlot() < 0 || proto.getSlot() >= receipt.slots().length) {
            return RecordClickResponse.newBuilder().setStatus(ClickStatus.CLICK_INVALID).build();
        }
        final AuctionReceiptStore.SlotReceipt slot = receipt.slots()[proto.getSlot()];
        final SpendAuthority wallet = wallets.get(slot.campaignId());
        if (wallet == null) {
            return RecordClickResponse.newBuilder().setStatus(ClickStatus.CLICK_INVALID).build();
        }
        ensureLease(slot.campaignId(), nowNanos);
        final ChargeResult result = ledger.charge(
                proto.getIdempotencyKey(),
                slot.campaignId(),
                receipt.hashCode(),
                slot.priceMicros(),
                nowNanos,
                wallet);
        return RecordClickResponse.newBuilder()
                .setStatus(toClickStatus(result.status()))
                .setChargedMicros(result.amountMicros())
                .setCampaignId(slot.campaignId())
                .build();
    }

    void ensureLeases(long nowNanos) {
        for (CampaignBudgetConfig config : coordinator.configs()) {
            ensureLease(config.campaignId(), nowNanos);
        }
    }

    private void ensureLease(long campaignId, long nowNanos) {
        final SpendAuthority wallet = wallets.get(campaignId);
        final CampaignBudgetConfig config = coordinator.config(campaignId);
        if (wallet == null || config == null) {
            return;
        }
        final long lowWater = Math.max(LOW_WATER_MICROS, config.bidMicros());
        if (!wallet.needsLease(nowNanos, lowWater, RENEW_AHEAD_NANOS)) {
            return;
        }
        final long heldId = wallet.leaseId();
        final long settleId;
        final long settleSpent;
        if (wallet.pendingReleaseId() != Lease.NONE) {
            settleId = wallet.pendingReleaseId();
            settleSpent = wallet.pendingReleaseSpentMicros();
        } else if (wallet.isExpired(nowNanos) || wallet.isSealed()
                || wallet.remainingMicros() < config.bidMicros()) {
            settleId = wallet.leaseId();
            settleSpent = wallet.sealForRenewal();
        } else {
            settleId = Lease.NONE;
            settleSpent = 0L;
        }
        final Lease lease = coordinator.requestLease(
                campaignId, shardId, incarnation, heldId, settleId, settleSpent, nowNanos);
        if (lease != null && wallet.installLease(lease, nowNanos)
                && wallet.pendingReleaseId() != Lease.NONE) {
            coordinator.releaseSealed(
                    campaignId,
                    shardId,
                    incarnation,
                    wallet.pendingReleaseId(),
                    wallet.pendingReleaseSpentMicros());
        }
        if (wallet.leaseId() != Lease.NONE) {
            coordinator.recordReport(
                    campaignId, shardId, incarnation, wallet.leaseId(), wallet.leaseSpentMicros());
        }
    }

    private boolean canAfford(long campaignId, long bidMicros, long nowNanos) {
        final SpendAuthority wallet = wallets.get(campaignId);
        if (wallet == null) {
            // Unknown to the budget config — allow through as an unbudgeted competitor.
            return true;
        }
        return !wallet.isSealed()
                && !wallet.isExpired(nowNanos)
                && wallet.remainingMicros() >= bidMicros;
    }

    private static ClickStatus toClickStatus(ChargeStatus status) {
        return switch (status) {
            case ACCEPTED -> ClickStatus.CLICK_CHARGED;
            case REPLAYED -> ClickStatus.CLICK_REPLAYED;
            case REFUSED -> ClickStatus.CLICK_REFUSED;
            case CONFLICT -> ClickStatus.CLICK_CONFLICT;
        };
    }

    @Override
    public void close() throws IOException {
        ledger.close();
    }
}
