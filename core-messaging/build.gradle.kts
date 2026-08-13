plugins {
    java
}

// core-messaging (M5a): zero dependencies, deliberately, same reasoning as core-filetransfer
// (M4a). HybridLogicalClock needs nothing beyond the JDK itself (java.time.Clock for the
// physical-time component, java.util.concurrent.atomic for the CAS-based state transition) —
// there is no maintained, published Java library for hybrid logical clocks to depend on
// instead (searched before deciding this; see the M5a section of README.md for what was
// actually found), so this is implemented directly against the algorithm in Kulkarni,
// Demirbas, Madeppa, Avva, Leone, "Logical Physical Clocks" (OPODIS 2014 / SUNY Buffalo Tech
// Report 2014-04), Figure 4.
//
// Verified by actually compiling and running this module's logic, not hand-traced — see the
// M5a section of README.md.
