package com.github.quiltservertools.ledger.actionutils

object RollbackLogGuard {
    private val suppressedDepth = ThreadLocal.withInitial { 0 }
    private val suppressedConflictDepth = ThreadLocal.withInitial { 0 }

    val isSuppressed: Boolean
        get() = suppressedDepth.get() > 0

    val isConflictCheckSuppressed: Boolean
        get() = suppressedConflictDepth.get() > 0

    fun <T> withSuppressedLogging(block: () -> T): T {
        suppressedDepth.set(suppressedDepth.get() + 1)
        return try {
            block()
        } finally {
            suppressedDepth.set(suppressedDepth.get() - 1)
        }
    }

    fun <T> withSuppressedConflictCheck(block: () -> T): T {
        suppressedConflictDepth.set(suppressedConflictDepth.get() + 1)
        return try {
            block()
        } finally {
            suppressedConflictDepth.set(suppressedConflictDepth.get() - 1)
        }
    }
}
