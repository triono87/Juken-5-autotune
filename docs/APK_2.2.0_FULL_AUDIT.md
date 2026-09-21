# Juken 5 APK 2.2.0 — Full Audit Plan / Current Findings

## Scope
Interoperability-oriented reverse engineering of the user-provided APK. The objective is to reproduce observable ECU-tuning behavior without bypassing security controls or copying proprietary assets.

## Confirmed from bytecode report
### Fuel-correction write
Three classes contain `kirimFuelCorrection(String)`:
- `folder_base_map/base_map`
- `folder_ignition/ignition_timing`
- `folder_injector/injector_timing`

Packet construction:
`2602;<argument>;<hitung_tps>;<61 values>\r\n`

The 61 values come from `MappingHandle.list_fuel`. The bytecode report found no checksum/CRC operation in this method.

### Other observable protocol markers
- `1602;`
- `2602;`
- `A603`
- Live path markers `160A` / `160B` are present in the broader implementation/references and remain hardware-validation items.

## Public corroboration
A public reverse-engineering Android project documents Juken 5 Classic SPP and the 1602/2602/A603 family, while explicitly warning that its write format is reverse-engineered and should be verified by read-back. This corroborates the general command family but does not make it official documentation.

## Product-feature model
Public Juken documentation/articles describe:
- Fuel correction
- Base map
- Ignition timing
- Injector timing
- E-map
- Limiter
- Live tuning
- AFR monitoring
- Diagnostic tools
- History/data logging
- Capture
- Multiple memories / cores on some Juken 5 variants

The exact feature set varies by Juken 5 generation/model, so the new project will expose capabilities based on verified APK behavior rather than assuming every variant has every feature.

## Remaining deep-analysis targets
1. Enumerate every class/method and map menu -> method -> command.
2. Resolve all constant strings and intent/service extras.
3. Trace every `1602` caller and `9602` response parser.
4. Trace every `2602` caller and determine the meaning of the second field (`argument`).
5. Resolve TPS row calculation and exact breakpoint ordering.
6. Resolve the 61-value index mapping and storage format.
7. Identify all A603 field positions and units by cross-checking UI labels.
8. Enumerate read/write operations for fuel, base, ignition, injector timing and parameters.
9. Identify ACK/error frames and timeout/retry behavior.
10. Map Bluetooth SPP lifecycle and service/Wi-Fi transport.
11. Reproduce the original app's observable behavior in a clean implementation.
12. Build a protocol test harness with captured packets before enabling real ECU writes.

## Safety / verification gate
No automatic ECU write is considered production-ready until:
- original-app TX is captured,
- reconstructed TX matches byte-for-byte,
- ECU response/ACK is captured,
- one-row or one-cell change is read back and matches,
- failure/timeout behavior is understood.

## Reference
- Public reverse-engineering repository: https://github.com/bedul19/Juken
- Public Juken feature documentation/articles: see research notes in the project history.
