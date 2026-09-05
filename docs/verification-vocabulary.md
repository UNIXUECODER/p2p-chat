# Verification vocabulary

> Added per finding D-3 of `pre-m6h-hardening-plan.md`. The problem it fixes wasn't the
> engineering — it was that a reader couldn't tell, from prose alone, which of four very
> different claims a "verified" or "confirmed" label was making. The README's M6g-4 section
> and `M6g-gap-analysis-and-plan.md §5` said contradictory things about the same milestone for
> exactly this reason. One vocabulary, defined once, used everywhere from here on.

| Badge | Meaning |
|:---|:---|
| ✅ **Verified (hardware)** | Executed on real hardware, against real dependencies (real `jvm-libp2p`, real `libsignal-client`, real SQLite) — not a sandbox, not a mock. Date and method recorded at the point of use. |
| 🧪 **Tested (CI)** / **Tested (local run)** | Automated tests executed and passed — `./gradlew test`, actual JUnit output, not asserted from memory. This is what most milestone entries from here on will carry, since the author of a given change frequently cannot reach a JDK/Maven Central in their own environment; the reader/reviewer running the real suite is what upgrades a change to this badge. |
| 🔨 **Compiles** | Builds cleanly (`javac`/`./gradlew compileJava` or equivalent). Not executed. Says nothing about correctness beyond syntax and types. |
| ✍️ **Reviewed** | Read carefully against the source, cross-checked, but not executed by the reviewer. This is the honest label for e.g. an audit pass that inspects code without running it. |

Rules for using it:

- **State the badge, not just a claim.** "39/39 tests passing" is ambiguous between 🧪 and 🔨
  unless the badge is attached — a compile-only pass and an executed pass can both produce a
  green checkmark in some tooling.
- **A lower badge never gets silently upgraded to a higher one.** If a change was 🔨 Compiles
  when written and later actually run, the entry gets *edited* to ✅ or 🧪 with the date of the
  real run — it doesn't get rewritten as if it always was. (This is exactly the discipline this
  document exists to enforce: see the corrected M6g-4 entry in
  `M6g-gap-analysis-and-plan.md §5` for a worked example — struck through and dated, not deleted.)
- **When work is hardware-verified by someone other than its author** (e.g. code implemented by
  an assistant, verified separately on real hardware by the project owner before being trusted),
  say so explicitly rather than implying the same party did both. That split is itself useful
  information about how much independent confirmation a change has.
