package dev.shehran.rawcull.device

/** R8 D12: the ≈ 2 minute set. `scripts/device-test.sh smoke` runs only these. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Smoke

/**
 * R8 D12: a known bug planned for a later round, e.g. `@KnownIssue("R9 loupe bar clips at 360 dp")`.
 * The test still runs and is reported, but its failure never fails the run.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class KnownIssue(val reason: String)
