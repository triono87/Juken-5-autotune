# Juken 5 APK 2.2.0 — Reverse Engineering Baseline

## Source
User-provided Juken 5 Android APK 2.2.0. Analysis is limited to interoperability/research of the ECU communication and application behavior. No credential, DRM, or security bypass is involved.

## Confirmed bytecode finding: kirimFuelCorrection()
Three methods were identified:
- folder_base_map/base_map->kirimFuelCorrection(String)
- folder_ignition/ignition_timing->kirimFuelCorrection(String)
- folder_injector/injector_timing->kirimFuelCorrection(String)

All three build the same ASCII packet shape:

2602;<argument>;<tps-row>;<61 values>\r\n

The method builds a StringBuilder, appends:
1. "2602;"
2. method argument p1
3. ";"
4. String.valueOf(hitung_tps)
5. 61 values from MappingHandle.list_fuel, separated by semicolons
6. CRLF terminator

The resulting String is converted to bytes and sent through Bluetooth output stream. A second transport path forwards the data through an Android service.

### Important
No CRC/checksum calculation was found inside these methods. Therefore this project must NOT add a guessed binary checksum to 2602.

## Related command strings found
- 1602;
- 2602;
- A603
- live-data markers 160A / 160B are used by the current interoperability implementation and must still be verified against real hardware.

## Verification status
- Packet construction: **bytecode-supported**
- Exact meaning of p1: **not yet independently verified**
- Exact meaning/index mapping of hitung_tps: **not yet independently verified**
- ECU ACK semantics: **not yet verified**
- Read-back behavior after write: **not yet verified**
- Real ECU hardware test: **pending**

## Safe implementation plan
1. Build exact 2602 packet without invented checksum.
2. Keep WRITE behind an explicit verification/test gate.
3. Capture one manual Fuel Correction transaction from the original app.
4. Compare TX packet byte-for-byte with this builder.
5. Capture ECU response.
6. Perform one-cell write -> response/ACK -> read-back -> compare.
7. Only after successful verification enable bulk map transfer.
