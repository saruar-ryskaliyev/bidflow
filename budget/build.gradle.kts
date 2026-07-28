plugins {
    id("bidflow.java-conventions")
}

description = "Distributed budget enforcement: a central authority granting spend authority to serving shards."

// Multi-shard simulation experiments live in :budget-sim. Production budget code stays
// free of the simulator so the same classes can run under sim and under real serving.

tasks.test {
    // Keep stdout from unit tests quiet unless a suite opts in.
}
