# TimeGate 0.34.58 / TG-FAIR-1

Completion validity, timing evidence quality and rank adjudication are separate. This is a pilot policy, not a guarantee of sub-second or <=3-second physical accuracy.

Install server 0.8.12 first, configure the fair policy before the official session, and rejoin the event from every phone to synchronize the policy ID. All phones should be fixed in a consistent handlebar position. Do not ride faster for the sake of GPS accuracy.

TG-GATE-1 computes signed distance to the fixed gate line, not clamped GPX endpoint progress. It uses a monotonic time base, requires a forward signed bracket, rejects missing/ambiguous evidence, and limits fitting to a short constant-motion neighbourhood. Later post-gate braking/acceleration is validation-only. A rejected correction retains the preliminary measurement and flags review; it is not silently forced into a nominal accuracy range.

Margins combine location, actual sampling gap, local-model disagreement and available timing-uncertainty inputs. They are uncapped OPERATIONAL estimates, not calibrated confidence intervals. Correlated GNSS bias can persist. No ground-truth on-road accuracy validation has yet been performed for this release.

FINISH displays 확인중 while collecting follow-up fixes, then saves the result and timing evidence locally before transmission. A durable pending-finish journal preserves an interrupted FINISH with UNKNOWN timing rather than inventing evidence. Next-lap detection does not wait for result-display animation: buffered fixes are replayed, and coincident aligned START/FINISH boundaries share their crossing time. Final-time display is a presentation-only hold while the engine is already ARMED. On-device/continuous-lap field testing is still required.

Only new-rule VALID completion + acceptable timing is used for the local new-rule best. Histories remain unchanged. The server independently replays the bounded START/FINISH evidence and applies policy/attempt/rank-range rules. A completion approval cannot reduce timing uncertainty. Official attempts include server-received STARTs even without FINISH; a journal that is lost or never uploads is not observable at the server.

Verification: deterministic unit tests cover stable motion, braking/acceleration after the gate, one-Hz input, gaps/missing evidence, duplicate fixes, strict gate width, low-speed unknowns, correction limits, mock source, unclamped signed geometry and uncapped margins. These tests validate behavior, not physical field accuracy.
