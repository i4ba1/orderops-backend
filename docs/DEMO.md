# Five-minute Jagaad demonstration

Run after default-profile verification, dependency audit and the unmocked browser journey pass. The earlier full-stack result predates the current release changes; rerun it for the exact release pair.

## Record the real application

Start the complete stack with at least six units in a SKU if running the E2E journey followed by this recording. From `orderops/frontend`:

```powershell
$env:ORDEROPS_DEMO_URL='http://127.0.0.1:8088'
npm run demo:record
```

The recorder uses real HTTP and UI actions, reserves three real inventory units, and creates a roughly five-minute captioned video at `frontend/.cache/demo/orderops-demo.webm` plus `evidence.json`. It checks success, same-key replay, manual recovery, and timeout reconciliation. Set `ORDEROPS_DEMO_USERNAME` and `ORDEROPS_DEMO_PASSWORD` for a nonlocal account. Use fictional data in an isolated demonstration environment. The password input is masked; no request traces or credentials are saved by this recorder.

The integration workflow records and uploads this artifact only after the actual journey succeeds. A failed recording is named `orderops-demo-incomplete.webm`. No completed video is claimed until that command runs against a usable backend. A recording is not evidence of cloud deployment, high availability, or live backup recovery.

## Presentation outline

1. **0:00 Product/boundaries:** show the operator dashboard, two applications and separate database ownership. Explain why inventory stays with orders for atomic reservations.
2. **0:45 Success:** create one COFFEE order with NORMAL behavior; inspect timeline and tracking. SHIPMENT_CREATED means accepted, not dispatched.
3. **1:30 Correctness:** replay the same key/payload; show200, the same ID and unchanged stock. Conflicting payload returns409. Show the real PostgreSQL held-lock contention test rather than claiming concurrency from a unit test.
4. **2:15 Recovery:** create FAIL_THEN_RECOVER; three attempts exhaust, order fails. Click Retry fulfillment; generation2 succeeds while stock remains reserved once.
5. **3:15 Lost response:** create TIMEOUT_AFTER_CREATE. Carrier persists before delaying. A stable order UUID key retrieves the same shipment after timeout. Private simulator statistics/unique shipment rows can verify the effect.
6. **4:00 Engineering:** show outbox/deduplication, leases, stale-generation protection, viewer restrictions and CI. Explain single-host tradeoffs and actual verification limits; finish on the requirement map.

Repository screenshots come from isolated UI fixtures. Capture real-stack screenshots after that journey runs, and distinguish fixture screenshots from deployment proof.
