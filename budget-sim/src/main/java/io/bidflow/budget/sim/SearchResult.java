package io.bidflow.budget.sim;

/**
 * Outcome of a single injected search on one shard.
 *
 * @param wonAuction true when our campaign cleared a slot
 * @param served true when the wallet committed spend for the cleared price
 * @param refused true when we won but the wallet could not afford the price
 * @param costMicros GSP price when {@code wonAuction}, otherwise zero
 */
public record SearchResult(boolean wonAuction, boolean served, boolean refused, long costMicros) {}
